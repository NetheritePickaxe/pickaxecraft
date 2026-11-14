package com.sun.jndi.dns;

/**
 * 仅宿主侧存在的占位实现，属于 M3 证据工具，不进入客户端产物。
 *
 * <p>{@code org.geysermc.mcprotocollib.network.helper.NettyHelper} 的静态初始化无条件
 * {@code Class.forName("com.sun.jndi.dns.DnsContextFactory")} 再 {@code new InitialDirContext(env)}，
 * 任何异常都会被包成 {@code RuntimeException} 抛在 {@code <clinit>} 里，而连接路径
 * （{@code ClientNetworkSession.connect} → {@code NettyHelper.resolveAddress}）必然触发它。
 * ART 上既没有 JDK 的 {@code com.sun.jndi.*}，也没有 {@code javax.naming}（API 36 实测），
 * 于是第一次连接就变成 {@code ExceptionInInitializerError} / {@code NoClassDefFoundError}：
 * 客户端一个包都发不出去。
 *
 * <p>宿主因此自带两处最小替代：本类让那次 {@code Class.forName} 成功；
 * {@code javax.naming.directory.InitialDirContext}（宿主 {@code javax/naming} 目录下的文件）
 * 让构造成功、把 SRV 查询变成一次受控的 {@code NamingException}。
 * {@code NettyHelper.resolveSrv} 捕获它、记一条 debug 日志后返回 null，
 * {@code resolveAddress} 回落到 {@code InetAddress.getByName} 的普通解析——
 * 这正是 Android 上真实可用的语义：不做 SRV，主机名与字面 IP 照常解析。
 *
 * <p>这不属于“为了让验证通过而改客户端”：客户端 API 一行没动，替换只发生在证据宿主的
 * dex 里。正确的上游修复是让 {@code NettyHelper} 延迟初始化 JNDI（或探测失败后跳过 SRV）；
 * 在那之前，任何 Android 宿主都必须自带同样的最小占位类，
 * 该约束记录在 {@code docs/architecture.md} 的 Android 约束与
 * {@code docs/plans/m3-android-runtime.md} 的结果里。
 */
public final class DnsContextFactory {

    private DnsContextFactory() {
    }
}
