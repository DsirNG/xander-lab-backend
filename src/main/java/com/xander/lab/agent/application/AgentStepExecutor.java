package com.xander.lab.agent.application;

import com.xander.lab.agent.domain.AgentStep;
import com.xander.lab.agent.infrastructure.AgentModelClient;
import com.xander.lab.common.ModelCallResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Executes one already-journaled model request and settles its durable step outcome. */
@Service
public class AgentStepExecutor {
    private final AgentModelClient modelClient;
    private final AgentExecutionJournal executionJournal;

    @Autowired
    public AgentStepExecutor(AgentModelClient modelClient, AgentExecutionJournal executionJournal) {
        this.modelClient = modelClient;
        this.executionJournal = executionJournal;
    }

    /** Compatibility factory for focused loop tests that do not bootstrap the Spring journal bean. */
    static AgentStepExecutor legacy(AgentModelClient modelClient) {
        return new AgentStepExecutor(modelClient, AgentExecutionJournal.disabled());
    }

    /**
     * Calls the configured model outside a database transaction.
     *
     * <p>The journal intent is already durable when this method begins. Every exit settles that
     * exact step before control returns, so a caller cannot report a model call without its
     * corresponding durable outcome.</p>
     */
    public ModelCallResult execute(AgentStep step, AgentStepRequest request,
                                   Consumer<String> onAnswerDelta,
                                   BooleanSupplier cancellationRequested) {
        try {
            ModelCallResult call = request.attachments().isEmpty()
                    ? modelClient.decideNextStepStream(request.context(), request.toolDescriptions(),
                            onAnswerDelta, cancellationRequested)
                    : modelClient.decideNextStepStream(request.context(), request.toolDescriptions(),
                            request.attachments(), onAnswerDelta, cancellationRequested);
            executionJournal.completeStep(step, call);
            return call;
        } catch (CancellationException exception) {
            executionJournal.cancelStep(step);
            throw exception;
        } catch (RuntimeException exception) {
            executionJournal.failStep(step, safeMessage(exception));
            throw exception;
        }
    }

    private String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return "模型调用失败";
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }
}
