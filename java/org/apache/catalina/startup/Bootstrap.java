/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.catalina.startup;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.catalina.Globals;
import org.apache.catalina.security.SecurityClassLoad;
import org.apache.catalina.startup.ClassLoaderFactory.Repository;
import org.apache.catalina.startup.ClassLoaderFactory.RepositoryType;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;


/**
 * 为卡特琳娜引导加载程序。这个应用程序构造了一个类加载器
 * 用于加载Catalina内部类(通过累积所有
 * 在“catalina.home”下的“server”目录中找到JAR文件)和
 * 启动容器的常规执行。这样做的目的
 * 迂回的方法是保留Catalina内部类(以及任何类)
 * 它们所依赖的其他类(如XML解析器)在系统之外类路径，因此对应用程序级类不可见。
 */
//Bootstrap：tomcat项目启动类
public final class Bootstrap {

    private static final Log log = LogFactory.getLog(Bootstrap.class);

    /**
     * main使用的守护进程对象。
     */
    private static final Object daemonLock = new Object();
    private static volatile Bootstrap daemon = null;

    //Catalina的一些文件
    private static final File catalinaBaseFile;
    private static final File catalinaHomeFile;

    //文件正则表达式
    private static final Pattern PATH_PATTERN = Pattern.compile("(\".*?\")|(([^,])*)");

    // 静态方法块, 做一些启动前的准备工作
    //静态代码块，Bootstrap.class类文件一被加载进内存，立即执行
    //初始一些tomcat容器所需的配置
    static {
        // 获取userDir路径名称
        String userDir = System.getProperty("user.dir");

        //获取home目录路径名称
        // 这个值在虚拟机选项中进行设置的 -Dcatalina.home=D:\my_project\catalina-home
        String home = System.getProperty(Globals.CATALINA_HOME_PROP);
        //home目录下的文件
        File homeFile = null;

        if (home != null) {
            //创建一个home文件File对象，获取home目录下的所有内容
            File f = new File(home);
            try {
                homeFile = f.getCanonicalFile();
            } catch (IOException ioe) {
                //获取home目录的绝对路径File
                homeFile = f.getAbsoluteFile();
            }
        }

        if (homeFile == null) {
            // First fall-back. See if current directory is a bin directory
            // in a normal Tomcat install
            //第一个备用。查看当前目录是否是正常Tomcat安装中的bin目录
            File bootstrapJar = new File(userDir, "bootstrap.jar");

            if (bootstrapJar.exists()) {
                File f = new File(userDir, "..");
                try {
                    homeFile = f.getCanonicalFile();
                } catch (IOException ioe) {
                    homeFile = f.getAbsoluteFile();
                }
            }
        }

        if (homeFile == null) {
            // Second fall-back. Use current directory
            File f = new File(userDir);
            try {
                homeFile = f.getCanonicalFile();
            } catch (IOException ioe) {
                homeFile = f.getAbsoluteFile();
            }
        }

        catalinaHomeFile = homeFile;
        System.setProperty(
                Globals.CATALINA_HOME_PROP, catalinaHomeFile.getPath());

        // Then base
        String base = System.getProperty(Globals.CATALINA_BASE_PROP);
        if (base == null) {
            catalinaBaseFile = catalinaHomeFile;
        } else {
            File baseFile = new File(base);
            try {
                baseFile = baseFile.getCanonicalFile();
            } catch (IOException ioe) {
                baseFile = baseFile.getAbsoluteFile();
            }
            catalinaBaseFile = baseFile;
        }
        System.setProperty(
                Globals.CATALINA_BASE_PROP, catalinaBaseFile.getPath());
    }

    // -------------------------------------------------------------- Variables


    /**
     * Daemon reference.
     */
    //卡特琳娜守护进程
    private Object catalinaDaemon = null;

    //一些类加载器
    ClassLoader commonLoader = null;
    ClassLoader catalinaLoader = null;
    ClassLoader sharedLoader = null;


    // -------------------------------------------------------- Private Methods


    //初始化一些类加载器
    private void initClassLoaders() {
        try {
            //创建commonLoader类加载器
            commonLoader = createClassLoader("common", null);
            if (commonLoader == null) {
                // no config file, default to this loader - we might be in a 'single' env.
                //没有配置文件，默认为这个加载器-我们可能在一个'单一'环境
                commonLoader = this.getClass().getClassLoader();
            }
            catalinaLoader = createClassLoader("server", commonLoader);
            sharedLoader = createClassLoader("shared", commonLoader);
        } catch (Throwable t) {
            handleThrowable(t);
            log.error("Class loader creation threw exception", t);
            //如果初始化类加载失败则退出程序
            System.exit(1);
        }
    }


    //根据类加载器的名称和他的父类，创建类加载器
    private ClassLoader createClassLoader(String name, ClassLoader parent)
            throws Exception {

        String value = CatalinaProperties.getProperty(name + ".loader");
        if ((value == null) || (value.equals("")))
            return parent;

        value = replace(value);

        List<Repository> repositories = new ArrayList<>();

        String[] repositoryPaths = getPaths(value);

        for (String repository : repositoryPaths) {
            // Check for a JAR URL repository
            //检查JAR URL存储库
            try {
                @SuppressWarnings("unused")
                URL url = new URL(repository);
                repositories.add(new Repository(repository, RepositoryType.URL));
                continue;
            } catch (MalformedURLException e) {
                // Ignore
            }

            // Local repository
            if (repository.endsWith("*.jar")) {
                repository = repository.substring
                        (0, repository.length() - "*.jar".length());
                repositories.add(new Repository(repository, RepositoryType.GLOB));
            } else if (repository.endsWith(".jar")) {
                repositories.add(new Repository(repository, RepositoryType.JAR));
            } else {
                repositories.add(new Repository(repository, RepositoryType.DIR));
            }
        }

        //使用类加载器工厂创建类加载器，并返回
        return ClassLoaderFactory.createClassLoader(repositories, parent);
    }


    /**
     * 在给定字符串中替换系统属性。
     *
     * @param str The original string
     * @return the modified string
     */
    protected String replace(String str) {
        // Implementation is copied from ClassLoaderLogManager.replace(),
        // but added special processing for catalina.home and catalina.base.
        String result = str;
        int pos_start = str.indexOf("${");
        if (pos_start >= 0) {
            StringBuilder builder = new StringBuilder();
            int pos_end = -1;
            while (pos_start >= 0) {
                builder.append(str, pos_end + 1, pos_start);
                pos_end = str.indexOf('}', pos_start + 2);
                if (pos_end < 0) {
                    pos_end = pos_start - 1;
                    break;
                }
                String propName = str.substring(pos_start + 2, pos_end);
                String replacement;
                if (propName.length() == 0) {
                    replacement = null;
                } else if (Globals.CATALINA_HOME_PROP.equals(propName)) {
                    replacement = getCatalinaHome();
                } else if (Globals.CATALINA_BASE_PROP.equals(propName)) {
                    replacement = getCatalinaBase();
                } else {
                    replacement = System.getProperty(propName);
                }
                if (replacement != null) {
                    builder.append(replacement);
                } else {
                    builder.append(str, pos_start, pos_end + 1);
                }
                pos_start = str.indexOf("${", pos_end + 1);
            }
            builder.append(str, pos_end + 1, str.length());
            result = builder.toString();
        }
        return result;
    }


    /**
     * 初始化守护进程。
     *
     * @throws Exception Fatal initialization error
     */
    public void init() throws Exception {

        //初始化一些类加载器
        initClassLoaders();

        //设置当前线程的上下文类加载器
        Thread.currentThread().setContextClassLoader(catalinaLoader);
        //将当类加载封装成安全的类加载器
        SecurityClassLoad.securityClassLoad(catalinaLoader);

        // Load our startup class and call its process() method
        //加载我们的startup类并调用它的process()方法
        if (log.isDebugEnabled())
            log.debug("Loading startup class");
        //获取org.apache.catalina.startup.Catalina的类字节码的Class对象
        Class<?> startupClass = catalinaLoader.loadClass("org.apache.catalina.startup.Catalina");
        //创建org.apache.catalina.startup.Catalina对象
        Object startupInstance = startupClass.getConstructor().newInstance();

        // Set the shared extensions class loader
        //设置共享扩展类加载器
        if (log.isDebugEnabled())
            log.debug("Setting startup class properties");
        //设置父类加载器的方法名
        String methodName = "setParentClassLoader";

        Class<?> paramTypes[] = new Class[1];
        paramTypes[0] = Class.forName("java.lang.ClassLoader");
        Object paramValues[] = new Object[1];
        paramValues[0] = sharedLoader;
        Method method =
                startupInstance.getClass().getMethod(methodName, paramTypes);
        //通过反射执行startupInstance方法，
        method.invoke(startupInstance, paramValues);

        //将org.apache.catalina.startup.Catalina对象赋给catalinaDaemon
        catalinaDaemon = startupInstance;
    }


    /**
     * Load daemon.
     */
    //加载org.apache.catalina.startup.Catalina
    private void load(String[] arguments) throws Exception {

        // Call the load() method
        String methodName = "load";
        Object param[];
        Class<?> paramTypes[];
        if (arguments == null || arguments.length == 0) {
            paramTypes = null;
            param = null;
        } else {
            paramTypes = new Class[1];
            paramTypes[0] = arguments.getClass();
            param = new Object[1];
            param[0] = arguments;
        }
        Method method =
                catalinaDaemon.getClass().getMethod(methodName, paramTypes);
        if (log.isDebugEnabled()) {
            log.debug("Calling startup class " + method);
        }
        method.invoke(catalinaDaemon, param);
    }


    /**
     * getServer() for configtest
     */
    //为测试配置获取Server
    private Object getServer() throws Exception {

        String methodName = "getServer";
        Method method = catalinaDaemon.getClass().getMethod(methodName);
        return method.invoke(catalinaDaemon);
    }


    // ----------------------------------------------------------- Main Program


    /**
     * Load the Catalina daemon.
     *
     * @param arguments Initialization arguments
     * @throws Exception Fatal initialization error
     */
    //初始化Catalina类
    public void init(String[] arguments) throws Exception {

        init();
        load(arguments);
    }


    /**
     * Start the Catalina daemon.
     *
     * @throws Exception Fatal start error
     */
    //启动org.apache.catalina.startup.Catalina进程
    public void start() throws Exception {
        if (catalinaDaemon == null) {
            init();
        }

        Method method = catalinaDaemon.getClass().getMethod("start", (Class[]) null);
        method.invoke(catalinaDaemon, (Object[]) null);
    }


    /**
     * Stop the Catalina Daemon.
     *
     * @throws Exception Fatal stop error
     */
    //停止org.apache.catalina.startup.Catalina类进程
    public void stop() throws Exception {
        Method method = catalinaDaemon.getClass().getMethod("stop", (Class[]) null);
        method.invoke(catalinaDaemon, (Object[]) null);
    }


    /**
     * Stop the standalone server.
     *
     * @throws Exception Fatal stop error
     */
    //停止独立服务器。
    public void stopServer() throws Exception {

        Method method =
                catalinaDaemon.getClass().getMethod("stopServer", (Class[]) null);
        method.invoke(catalinaDaemon, (Object[]) null);
    }


    /**
     * Stop the standalone server.
     *
     * @param arguments Command line arguments
     * @throws Exception Fatal stop error
     */
    //根据命令停止服务器
    public void stopServer(String[] arguments) throws Exception {

        Object param[];
        Class<?> paramTypes[];
        if (arguments == null || arguments.length == 0) {
            paramTypes = null;
            param = null;
        } else {
            paramTypes = new Class[1];
            paramTypes[0] = arguments.getClass();
            param = new Object[1];
            param[0] = arguments;
        }
        Method method =
                catalinaDaemon.getClass().getMethod("stopServer", paramTypes);
        method.invoke(catalinaDaemon, param);
    }


    /**
     * Set flag.
     *
     * @param await <code>true</code> if the daemon should block
     * @throws Exception Reflection error
     */
    //设置等待标志
    public void setAwait(boolean await)
            throws Exception {

        Class<?> paramTypes[] = new Class[1];
        paramTypes[0] = Boolean.TYPE;
        Object paramValues[] = new Object[1];
        paramValues[0] = Boolean.valueOf(await);
        Method method =
                catalinaDaemon.getClass().getMethod("setAwait", paramTypes);
        method.invoke(catalinaDaemon, paramValues);
    }

    public boolean getAwait() throws Exception {
        Class<?> paramTypes[] = new Class[0];
        Object paramValues[] = new Object[0];
        Method method =
                catalinaDaemon.getClass().getMethod("getAwait", paramTypes);
        Boolean b = (Boolean) method.invoke(catalinaDaemon, paramValues);
        return b.booleanValue();
    }


    /**
     * Destroy the Catalina Daemon.
     */
    //摧毁卡特琳娜守护进程。
    public void destroy() {

        // FIXME

    }


    /**
     * Main method and entry point when starting Tomcat via the provided
     * scripts.
     *
     * @param args Command line arguments to be processed
     */
    public static void main(String args[]) {

        //使用线程同步
        synchronized (daemonLock) {
            //如果Bootstrap对象为null
            if (daemon == null) {
                // Don't set daemon until init() has completed
                //在init()完成之前不要设置守护进程
                Bootstrap bootstrap = new Bootstrap();
                try {
                    //执行该类的init()方法，初始化一些类加载和执行org.apache.catalina.startup.Catalina中方法
                    bootstrap.init();
                } catch (Throwable t) {
                    //如果有异常抛出停止程序执行
                    handleThrowable(t);
                    //打印异常信息
                    t.printStackTrace();
                    //介绍该main方法
                    return;
                }
                //如果daemon为null。将上面创建的Bootstrap对象赋给daemon作为守护进程
                daemon = bootstrap;
            } else {
                // When running as a service the call to stop will be on a new
                // thread so make sure the correct class loader is used to
                // prevent a range of class not found exceptions.
                //当作为服务运行时，要停止的调用将位于一个新线程上，因此请确保使用了正确的类加载器，以防止出现一系列未发现的类异常。
                Thread.currentThread().setContextClassLoader(daemon.catalinaLoader);
            }
        }

        //根据命令执行服务器的相应操作(开启、停止服务器)
        try {
            String command = "start";
            if (args.length > 0) {
                command = args[args.length - 1];
            }

            if (command.equals("startd")) {
                args[args.length - 1] = "start";
                daemon.load(args);
                daemon.start();
            } else if (command.equals("stopd")) {
                args[args.length - 1] = "stop";
                daemon.stop();
            } else if (command.equals("start")) {
                daemon.setAwait(true);
                daemon.load(args);
                daemon.start();
                if (null == daemon.getServer()) {
                    System.exit(1);
                }
            } else if (command.equals("stop")) {
                daemon.stopServer(args);
            } else if (command.equals("configtest")) {
                daemon.load(args);
                if (null == daemon.getServer()) {
                    System.exit(1);
                }
                System.exit(0);
            } else {
                log.warn("Bootstrap: command \"" + command + "\" does not exist.");
            }
        } catch (Throwable t) {
            // Unwrap the Exception for clearer error reporting
            if (t instanceof InvocationTargetException &&
                    t.getCause() != null) {
                t = t.getCause();
            }
            handleThrowable(t);
            t.printStackTrace();
            System.exit(1);
        }
    }


    /**
     * Obtain the name of configured home (binary) directory. Note that home and
     * base may be the same (and are by default).
     *
     * @return the catalina home
     */
    public static String getCatalinaHome() {
        return catalinaHomeFile.getPath();
    }


    /**
     * Obtain the name of the configured base (instance) directory. Note that
     * home and base may be the same (and are by default). If this is not set
     * the value returned by {@link #getCatalinaHome()} will be used.
     *
     * @return the catalina base
     */
    public static String getCatalinaBase() {
        return catalinaBaseFile.getPath();
    }


    /**
     * Obtain the configured home (binary) directory. Note that home and
     * base may be the same (and are by default).
     *
     * @return the catalina home as a file
     */
    public static File getCatalinaHomeFile() {
        return catalinaHomeFile;
    }


    /**
     * Obtain the configured base (instance) directory. Note that
     * home and base may be the same (and are by default). If this is not set
     * the value returned by {@link #getCatalinaHomeFile()} will be used.
     *
     * @return the catalina base as a file
     */
    public static File getCatalinaBaseFile() {
        return catalinaBaseFile;
    }


    // Copied from ExceptionUtils since that class is not visible during start
    private static void handleThrowable(Throwable t) {
        if (t instanceof ThreadDeath) {
            throw (ThreadDeath) t;
        }
        if (t instanceof VirtualMachineError) {
            throw (VirtualMachineError) t;
        }
        // All other instances of Throwable will be silently swallowed
    }


    // Protected for unit testing
    protected static String[] getPaths(String value) {

        List<String> result = new ArrayList<>();
        Matcher matcher = PATH_PATTERN.matcher(value);

        while (matcher.find()) {
            String path = value.substring(matcher.start(), matcher.end());

            path = path.trim();
            if (path.length() == 0) {
                continue;
            }

            char first = path.charAt(0);
            char last = path.charAt(path.length() - 1);

            if (first == '"' && last == '"' && path.length() > 1) {
                path = path.substring(1, path.length() - 1);
                path = path.trim();
                if (path.length() == 0) {
                    continue;
                }
            } else if (path.contains("\"")) {
                // Unbalanced quotes
                // Too early to use standard i18n support. The class path hasn't
                // been configured.
                throw new IllegalArgumentException(
                        "The double quote [\"] character only be used to quote paths. It must " +
                                "not appear in a path. This loader path is not valid: [" + value + "]");
            } else {
                // Not quoted - NO-OP
            }

            result.add(path);
        }

        return result.toArray(new String[result.size()]);
    }
}
