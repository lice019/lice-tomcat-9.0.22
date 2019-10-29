package javax.servlet.jsp;

import javax.servlet.Servlet;

/**
 * JspPage：是JSP的规范接口，用于对一个Page页面文件的处理接口方法规范处理
 * 一个JSP页面被编译后，是生成一个Servlet的。
 * 该接口继承了Servlet接口
 */


public interface JspPage extends Servlet {

    /*
     * JSP页面初始化的方法，会对一个JSP页面文件进行初始化
     */
    public void jspInit();

    /*
     * JSP的销毁方法
     */
    public void jspDestroy();

}
