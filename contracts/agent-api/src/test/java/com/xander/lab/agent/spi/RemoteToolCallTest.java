package com.xander.lab.agent.spi;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住 {@link RemoteToolCall} 的宽度与完整签名。
 *
 * <p>这个类型只有两个方法，看起来不值得单独测。但它是三个类型里<b>最容易被"顺手加一个
 * default"</b>的一个：它是回调，而回调天然让人想给"可选能力"加默认空实现
 * （"没有子调用用量要上报就别覆写")。加下去的代价是端口静默变宽，而且加的那个能力
 * 从此没人保证实现——比直接不提供更糟，因为它看起来存在。</p>
 */
class RemoteToolCallTest {

    private static final List<String> EXPECTED_SIGNATURES = List.of(
            "onProgress(String) -> void",
            "checkCancellation() -> void");

    @Test
    void theChannelDeclaresExactlyTheTwoKnownMethodsWithTheseExactSignatures() {
        List<String> actual = ContractShape.declaredSignatures(RemoteToolCall.class);

        assertEquals(EXPECTED_SIGNATURES.stream().sorted().toList(), actual,
                "通道宽度或签名变了。agent 的 ProgressListener 还有 onToolDelta / onModelUsage / "
                        + "bindInvocationId / isCancellationRequested，远端工具一个都不用——"
                        + "端口宽一分，每个实现就多一个空方法");
        assertEquals(2, actual.size(), "方法个数必须是 2");
    }

    @Test
    void noMethodIsDefaultBecauseThatWouldWidenTheContractSilently() {
        ContractShape.assertNoDefaultMethods(RemoteToolCall.class);
    }

    @Test
    void theChannelIsAnInterfaceInTheContractPackage() {
        assertTrue(RemoteToolCall.class.isInterface(), "通道必须是接口");
        assertEquals("com.xander.lab.agent.spi", RemoteToolCall.class.getPackageName());
        assertEquals(0, RemoteToolCall.class.getInterfaces().length,
                "通道不该继承别的接口：那会让宽度从别处漏进来");
    }

    @Test
    void noMethodSignatureLeaksAFrameworkType() {
        ContractShape.assertNoFrameworkTypes(RemoteToolCall.class);
    }

    /**
     * {@code checkCancellation()} 必须是 {@code void} 且<b>不声明受检异常</b>。
     *
     * <p>它靠抛 {@code java.util.concurrent.CancellationException}（非受检）来表达取消。
     * 一旦声明 {@code throws}，每个实现都得在签名上跟着写，而 {@code RemoteTool.invoke}
     * 的实现里那两次调用会变成必须 try/catch —— 一次本来该直接冒出去的取消会被就地吞掉。</p>
     */
    @Test
    void checkCancellationIsVoidAndThrowsOnlyUnchecked() throws ReflectiveOperationException {
        var method = RemoteToolCall.class.getMethod("checkCancellation");

        assertEquals(void.class, method.getReturnType());
        assertEquals(0, method.getExceptionTypes().length,
                "取消靠抛非受检的 CancellationException，不要声明受检异常");
    }

    @Test
    void theExpectedSignatureListHasNoDuplicates() {
        assertEquals(EXPECTED_SIGNATURES.size(), EXPECTED_SIGNATURES.stream().distinct().count(),
                "EXPECTED_SIGNATURES 里不能有重复项");
    }
}
