package javax.naming;

/**
 * 仅宿主侧的最小占位类，属于 M3 证据工具，不进入客户端产物。
 *
 * <p>Android 的 {@code android.jar} 里没有任何 {@code javax.naming} 类（API 36 实测），
 * 而 mcprotocollib 的 {@code NettyHelper.<clinit>} 会在连接时无条件创建
 * {@code InitialDirContext}。宿主必须自带这几个类型，才能让那次静态初始化通过；
 * 完整背景与上游修复方向见 {@code com.sun.jndi.dns.DnsContextFactory}。
 */
public class NamingException extends Exception {

    private static final long serialVersionUID = 1L;

    public NamingException() {
        super();
    }

    public NamingException(String message) {
        super(message);
    }
}
