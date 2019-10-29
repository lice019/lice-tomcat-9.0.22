
package javax.servlet;

import java.io.IOException;

/**
 * Servlet：该接口是所有Servlet规范接口，所有的Servlet都必须直接或间接实现该接口
 *
 * @see GenericServlet
 * @see javax.servlet.http.HttpServlet
 */
public interface Servlet {

    /*
     * Servlet初始化方法，Servlet需要改方法进行初始化
     * 在Servlet初始化时，设置Servlet的配置类
     * 可以通过该方法来实现Servlet初始化的前置处理（加载一下数据）
     */
    public void init(ServletConfig config) throws ServletException;

    /*
     * 获取该Servlet的配置信息
     */
    public ServletConfig getServletConfig();

    /*
     * service方法是Servlet最核心的方法，用于处理请求和响应的业务的
     * 处理doPost和doGet方法
     */
    public void service(ServletRequest req, ServletResponse res)
            throws ServletException, IOException;

    /*
     * 返回Servlet的信息
     */
    public String getServletInfo();

    /*
     * Servlet的销毁方法，Servlet在销毁时会执行该方法，进行对该Servlet进行销毁处理
     */
    public void destroy();
}
