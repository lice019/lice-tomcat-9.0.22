package javax.servlet;

import java.io.IOException;
import java.util.Enumeration;

/**
 * GenericServlet:一个Servlet的抽象类，用于一个web协议请求相应的Servlet，
 * 可通过继承该类，对该类的方法进行重写来开发Servlet
 */
public abstract class GenericServlet implements Servlet, ServletConfig,
        java.io.Serializable {

    private static final long serialVersionUID = 1L;

    //Servlet的配置类对象，定义一个Servlet的配置信息
    private transient ServletConfig config;

    /*
     * GenericServlet的构造器，所有子类Servlet的初始化都必须初始化该构造器
     * 什么事也做，用于Servlet的规范
     */
    public GenericServlet() {
        // NOOP
    }

    //重写Servlet接口的销毁方法
    @Override
    public void destroy() {
        // NOOP by default
    }

    /*
     * 通过参数name，从Servlet配置中获取初始化需要的参数
     */
    @Override
    public String getInitParameter(String name) {
        return getServletConfig().getInitParameter(name);
    }

    /*
     * 获取Servlet配置中参数的name，返回String类型的枚举类
     */
    @Override
    public Enumeration<String> getInitParameterNames() {
        return getServletConfig().getInitParameterNames();
    }

    //获取Servlet的配置信息
    @Override
    public ServletConfig getServletConfig() {
        return config;
    }

    /*
     * 获取一个Servlet的上下文，Servlet上下文具有操作Servlet所有信息的能力
     */
    @Override
    public ServletContext getServletContext() {
        return getServletConfig().getServletContext();
    }


    @Override
    public String getServletInfo() {
        return "";
    }


    //实现Servlet接口的初始化Servlet的方法，Servlet初始化之初会加重Servlet的配置
    @Override
    public void init(ServletConfig config) throws ServletException {
        this.config = config;
        this.init();
    }

    //GenericServlet内部定义的init方法
    public void init() throws ServletException {
        // NOOP by default
    }

    //日志信息，输出到日志文件中
    public void log(String msg) {
        getServletContext().log(getServletName() + ": " + msg);
    }

    //日志信息
    public void log(String message, Throwable t) {
        getServletContext().log(getServletName() + ": " + message, t);
    }

    //实现Servlet的service方法
    @Override
    public abstract void service(ServletRequest req, ServletResponse res)
            throws ServletException, IOException;

    //从配置中获取Servlet的名称name
    @Override
    public String getServletName() {
        return config.getServletName();
    }
}
