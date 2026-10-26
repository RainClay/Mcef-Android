package com.cinemamod.mcef.listeners;

/**
 * 引擎初始化完成回调。
 *
 * <p><b>签名必须与上游逐字一致</b>：WebDisplays 的
 * {@code MCEF.scheduleForInit((cef) -> onCefInit())} 会在编译期针对上游的
 * 函数式接口生成 lambda 类。如果这里的抽象方法名或参数类型不同，运行期就是
 * {@code AbstractMethodError}：</p>
 * <pre>
 * Receiver class ...$$Lambda$4843 does not define or inherit an implementation
 * of the resolved method 'abstract void onCefInit(MCEFApp)' of interface MCEFInitListener
 * </pre>
 *
 * <p>上游定义是 {@code void onInit(boolean successful)} —— 注意方法名是
 * {@code onInit} 而不是 {@code onCefInit}，参数是 boolean 而不是 MCEFApp。</p>
 */
@FunctionalInterface
public interface MCEFInitListener {

    /**
     * @param successful 引擎是否初始化成功
     */
    void onInit(boolean successful);
}
