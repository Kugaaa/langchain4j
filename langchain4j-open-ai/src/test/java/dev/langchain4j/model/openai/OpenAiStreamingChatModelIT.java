package dev.langchain4j.model.openai;

import dev.ai4j.openai4j.OpenAiHttpException;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.Tokenizer;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.chat.TestStreamingResponseHandler;
import dev.langchain4j.model.chat.listener.*;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import org.assertj.core.data.Percentage;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static dev.langchain4j.agent.tool.JsonSchemaProperty.INTEGER;
import static dev.langchain4j.data.message.ToolExecutionResultMessage.from;
import static dev.langchain4j.data.message.UserMessage.userMessage;
import static dev.langchain4j.internal.Utils.readBytes;
import static dev.langchain4j.model.openai.OpenAiChatModelIT.CAT_IMAGE_URL;
import static dev.langchain4j.model.openai.OpenAiChatModelIT.DICE_IMAGE_URL;
import static dev.langchain4j.model.openai.OpenAiChatModelName.*;
import static dev.langchain4j.model.openai.OpenAiModelName.GPT_3_5_TURBO_1106;
import static dev.langchain4j.model.output.FinishReason.STOP;
import static dev.langchain4j.model.output.FinishReason.TOOL_EXECUTION;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Fail.fail;
import static org.assertj.core.data.Percentage.withPercentage;

class OpenAiStreamingChatModelIT {

    OpenAiStreamingChatModel model = OpenAiStreamingChatModel.builder()
            .baseUrl(System.getenv("OPENAI_BASE_URL"))
            .apiKey(System.getenv("OPENAI_API_KEY"))
            .organizationId(System.getenv("OPENAI_ORGANIZATION_ID"))
            .temperature(0.0)
            .logRequests(true)
            .logResponses(true)
            .build();

    StreamingChatLanguageModel visionModel = OpenAiStreamingChatModel.builder()
            .baseUrl(System.getenv("OPENAI_BASE_URL"))
            .apiKey(System.getenv("OPENAI_API_KEY"))
            .organizationId(System.getenv("OPENAI_ORGANIZATION_ID"))
            .modelName(GPT_4_O_MINI)
            .temperature(0.0)
            .logRequests(true)
            .logResponses(true)
            .build();

    ToolSpecification calculator = ToolSpecification.builder()
            .name("calculator")
            .description("returns a sum of two numbers")
            .addParameter("first", INTEGER)
            .addParameter("second", INTEGER)
            .build();

    Percentage tokenizerPrecision = withPercentage(5);

    @Test
    void should_stream_answer() throws Exception {

        CompletableFuture<String> futureAnswer = new CompletableFuture<>();
        CompletableFuture<Response<AiMessage>> futureResponse = new CompletableFuture<>();

        model.generate("What is the capital of Germany?", new StreamingResponseHandler<AiMessage>() {

            private final StringBuilder answerBuilder = new StringBuilder();

            @Override
            public void onNext(String token) {
                System.out.println("onNext: '" + token + "'");
                answerBuilder.append(token);
            }

            @Override
            public void onComplete(Response<AiMessage> response) {
                System.out.println("onComplete: '" + response + "'");
                futureAnswer.complete(answerBuilder.toString());
                futureResponse.complete(response);
            }

            @Override
            public void onError(Throwable error) {
                futureAnswer.completeExceptionally(error);
                futureResponse.completeExceptionally(error);
            }
        });

        String answer = futureAnswer.get(30, SECONDS);
        Response<AiMessage> response = futureResponse.get(30, SECONDS);

        assertThat(answer).contains("Berlin");
        assertThat(response.content().text()).isEqualTo(answer);

        TokenUsage tokenUsage = response.tokenUsage();
        assertThat(tokenUsage.inputTokenCount()).isEqualTo(14);
        assertThat(tokenUsage.outputTokenCount()).isGreaterThan(0);
        assertThat(tokenUsage.totalTokenCount())
                .isEqualTo(tokenUsage.inputTokenCount() + tokenUsage.outputTokenCount());

        assertThat(response.finishReason()).isEqualTo(STOP);
    }

    @Test
    void should_execute_a_tool_then_stream_answer() throws Exception {

        // given
        UserMessage userMessage = userMessage("2+2=?");
        List<ToolSpecification> toolSpecifications = singletonList(calculator);

        // when
        CompletableFuture<Response<AiMessage>> futureResponse = new CompletableFuture<>();

        model.generate(singletonList(userMessage), toolSpecifications, new StreamingResponseHandler<AiMessage>() {

            @Override
            public void onNext(String token) {
                System.out.println("onNext: '" + token + "'");
                Exception e = new IllegalStateException("onNext() should never be called when tool is executed");
                futureResponse.completeExceptionally(e);
            }

            @Override
            public void onComplete(Response<AiMessage> response) {
                System.out.println("onComplete: '" + response + "'");
                futureResponse.complete(response);
            }

            @Override
            public void onError(Throwable error) {
                futureResponse.completeExceptionally(error);
            }
        });

        Response<AiMessage> response = futureResponse.get(30, SECONDS);
        AiMessage aiMessage = response.content();

        // then
        assertThat(aiMessage.text()).isNull();

        List<ToolExecutionRequest> toolExecutionRequests = aiMessage.toolExecutionRequests();
        assertThat(toolExecutionRequests).hasSize(1);

        ToolExecutionRequest toolExecutionRequest = toolExecutionRequests.get(0);
        assertThat(toolExecutionRequest.name()).isEqualTo("calculator");
        assertThat(toolExecutionRequest.arguments()).isEqualToIgnoringWhitespace("{\"first\": 2, \"second\": 2}");

        TokenUsage tokenUsage = response.tokenUsage();
        assertThat(tokenUsage.inputTokenCount()).isCloseTo(53, tokenizerPrecision);
        assertThat(tokenUsage.outputTokenCount()).isCloseTo(14, tokenizerPrecision);
        assertThat(tokenUsage.totalTokenCount())
                .isEqualTo(tokenUsage.inputTokenCount() + tokenUsage.outputTokenCount());

        assertThat(response.finishReason()).isEqualTo(TOOL_EXECUTION);

        // given
        ToolExecutionResultMessage toolExecutionResultMessage = from(toolExecutionRequest, "4");

        List<ChatMessage> messages = asList(userMessage, aiMessage, toolExecutionResultMessage);

        // when
        CompletableFuture<Response<AiMessage>> secondFutureResponse = new CompletableFuture<>();

        model.generate(messages, new StreamingResponseHandler<AiMessage>() {

            @Override
            public void onNext(String token) {
                System.out.println("onNext: '" + token + "'");
            }

            @Override
            public void onComplete(Response<AiMessage> response) {
                System.out.println("onComplete: '" + response + "'");
                secondFutureResponse.complete(response);
            }

            @Override
            public void onError(Throwable error) {
                secondFutureResponse.completeExceptionally(error);
            }
        });

        Response<AiMessage> secondResponse = secondFutureResponse.get(30, SECONDS);
        AiMessage secondAiMessage = secondResponse.content();

        // then
        assertThat(secondAiMessage.text()).contains("4");
        assertThat(secondAiMessage.toolExecutionRequests()).isNull();

        TokenUsage secondTokenUsage = secondResponse.tokenUsage();
        assertThat(secondTokenUsage.inputTokenCount()).isCloseTo(33, tokenizerPrecision);
        assertThat(secondTokenUsage.outputTokenCount()).isGreaterThan(0);
        assertThat(secondTokenUsage.totalTokenCount())
                .isEqualTo(secondTokenUsage.inputTokenCount() + secondTokenUsage.outputTokenCount());

        assertThat(secondResponse.finishReason()).isEqualTo(STOP);
    }

    @Test
    void should_execute_tool_forcefully_then_stream_answer() throws Exception {

        // given
        UserMessage userMessage = userMessage("2+2=?");

        // when
        CompletableFuture<Response<AiMessage>> futureResponse = new CompletableFuture<>();

        model.generate(singletonList(userMessage), calculator, new StreamingResponseHandler<AiMessage>() {

            @Override
            public void onNext(String token) {
                System.out.println("onNext: '" + token + "'");
                Exception e = new IllegalStateException("onNext() should never be called when tool is executed");
                futureResponse.completeExceptionally(e);
            }

            @Override
            public void onComplete(Response<AiMessage> response) {
                System.out.println("onComplete: '" + response + "'");
                futureResponse.complete(response);
            }

            @Override
            public void onError(Throwable error) {
                futureResponse.completeExceptionally(error);
            }
        });

        Response<AiMessage> response = futureResponse.get(30, SECONDS);
        AiMessage aiMessage = response.content();

        // then
        assertThat(aiMessage.text()).isNull();

        List<ToolExecutionRequest> toolExecutionRequests = aiMessage.toolExecutionRequests();
        assertThat(toolExecutionRequests).hasSize(1);

        ToolExecutionRequest toolExecutionRequest = toolExecutionRequests.get(0);
        assertThat(toolExecutionRequest.name()).isEqualTo("calculator");
        assertThat(toolExecutionRequest.arguments()).isEqualToIgnoringWhitespace("{\"first\": 2, \"second\": 2}");

        TokenUsage tokenUsage = response.tokenUsage();
        assertThat(tokenUsage.inputTokenCount()).isCloseTo(59, tokenizerPrecision);
        assertThat(tokenUsage.outputTokenCount()).isCloseTo(9, tokenizerPrecision);
        assertThat(tokenUsage.totalTokenCount())
                .isEqualTo(tokenUsage.inputTokenCount() + tokenUsage.outputTokenCount());

        assertThat(response.finishReason()).isEqualTo(STOP); // not sure if a bug in OpenAI or stop is expected here

        // given
        ToolExecutionResultMessage toolExecutionResultMessage = from(toolExecutionRequest, "4");

        List<ChatMessage> messages = asList(userMessage, aiMessage, toolExecutionResultMessage);

        // when
        CompletableFuture<Response<AiMessage>> secondFutureResponse = new CompletableFuture<>();

        model.generate(messages, new StreamingResponseHandler<AiMessage>() {

            @Override
            public void onNext(String token) {
                System.out.println("onNext: '" + token + "'");
            }

            @Override
            public void onComplete(Response<AiMessage> response) {
                System.out.println("onComplete: '" + response + "'");
                secondFutureResponse.complete(response);
            }

            @Override
            public void onError(Throwable error) {
                secondFutureResponse.completeExceptionally(error);
            }
        });

        Response<AiMessage> secondResponse = secondFutureResponse.get(30, SECONDS);
        AiMessage secondAiMessage = secondResponse.content();

        // then
        assertThat(secondAiMessage.text()).contains("4");
        assertThat(secondAiMessage.toolExecutionRequests()).isNull();

        TokenUsage secondTokenUsage = secondResponse.tokenUsage();
        assertThat(secondTokenUsage.inputTokenCount()).isCloseTo(33, tokenizerPrecision);
        assertThat(secondTokenUsage.outputTokenCount()).isGreaterThan(0);
        assertThat(secondTokenUsage.totalTokenCount())
                .isEqualTo(secondTokenUsage.inputTokenCount() + secondTokenUsage.outputTokenCount());

        assertThat(secondResponse.finishReason()).isEqualTo(STOP);
    }

    @Test
    void should_execute_multiple_tools_in_parallel_then_stream_answer() throws Exception {

        // given
        StreamingChatLanguageModel model = OpenAiStreamingChatModel.builder()
                .baseUrl(System.getenv("OPENAI_BASE_URL"))
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .organizationId(System.getenv("OPENAI_ORGANIZATION_ID"))
                .modelName(GPT_3_5_TURBO_1106)  // supports parallel function calling
                .temperature(0.0)
                .logRequests(true)
                .logResponses(true)
                .build();

        UserMessage userMessage = userMessage("2+2=? 3+3=?");
        List<ToolSpecification> toolSpecifications = singletonList(calculator);

        // when
        CompletableFuture<Response<AiMessage>> futureResponse = new CompletableFuture<>();

        model.generate(singletonList(userMessage), toolSpecifications, new StreamingResponseHandler<AiMessage>() {

            @Override
            public void onNext(String token) {
                System.out.println("onNext: '" + token + "'");
                Exception e = new IllegalStateException("onNext() should never be called when tool is executed");
                futureResponse.completeExceptionally(e);
            }

            @Override
            public void onComplete(Response<AiMessage> response) {
                System.out.println("onComplete: '" + response + "'");
                futureResponse.complete(response);
            }

            @Override
            public void onError(Throwable error) {
                futureResponse.completeExceptionally(error);
            }
        });

        Response<AiMessage> response = futureResponse.get(30, SECONDS);
        AiMessage aiMessage = response.content();

        // then
        assertThat(aiMessage.text()).isNull();
        assertThat(aiMessage.toolExecutionRequests()).hasSize(2);

        ToolExecutionRequest toolExecutionRequest1 = aiMessage.toolExecutionRequests().get(0);
        assertThat(toolExecutionRequest1.name()).isEqualTo("calculator");
        assertThat(toolExecutionRequest1.arguments()).isEqualToIgnoringWhitespace("{\"first\": 2, \"second\": 2}");

        ToolExecutionRequest toolExecutionRequest2 = aiMessage.toolExecutionRequests().get(1);
        assertThat(toolExecutionRequest2.name()).isEqualTo("calculator");
        assertThat(toolExecutionRequest2.arguments()).isEqualToIgnoringWhitespace("{\"first\": 3, \"second\": 3}");

        TokenUsage tokenUsage = response.tokenUsage();
        assertThat(tokenUsage.inputTokenCount()).isCloseTo(57, tokenizerPrecision);
        assertThat(tokenUsage.outputTokenCount()).isCloseTo(34, tokenizerPrecision);
        assertThat(tokenUsage.totalTokenCount())
                .isEqualTo(tokenUsage.inputTokenCount() + tokenUsage.outputTokenCount());

        assertThat(response.finishReason()).isEqualTo(TOOL_EXECUTION);

        // given
        ToolExecutionResultMessage toolExecutionResultMessage1 = from(toolExecutionRequest1, "4");
        ToolExecutionResultMessage toolExecutionResultMessage2 = from(toolExecutionRequest2, "6");

        List<ChatMessage> messages = asList(userMessage, aiMessage, toolExecutionResultMessage1, toolExecutionResultMessage2);

        // when
        CompletableFuture<Response<AiMessage>> secondFutureResponse = new CompletableFuture<>();

        model.generate(messages, new StreamingResponseHandler<AiMessage>() {

            @Override
            public void onNext(String token) {
                System.out.println("onNext: '" + token + "'");
            }

            @Override
            public void onComplete(Response<AiMessage> response) {
                System.out.println("onComplete: '" + response + "'");
                secondFutureResponse.complete(response);
            }

            @Override
            public void onError(Throwable error) {
                secondFutureResponse.completeExceptionally(error);
            }
        });

        Response<AiMessage> secondResponse = secondFutureResponse.get(30, SECONDS);
        AiMessage secondAiMessage = secondResponse.content();

        // then
        assertThat(secondAiMessage.text()).contains("4", "6");
        assertThat(secondAiMessage.toolExecutionRequests()).isNull();

        TokenUsage secondTokenUsage = secondResponse.tokenUsage();
        assertThat(secondTokenUsage.inputTokenCount()).isCloseTo(88, tokenizerPrecision);
        assertThat(secondTokenUsage.outputTokenCount()).isGreaterThan(0);
        assertThat(secondTokenUsage.totalTokenCount())
                .isEqualTo(secondTokenUsage.inputTokenCount() + secondTokenUsage.outputTokenCount());

        assertThat(secondResponse.finishReason()).isEqualTo(STOP);
    }

    @Test
    void should_stream_valid_json() throws Exception {

        //given
        String userMessage = "Return JSON with two fields: name and surname of Klaus Heisler. " +
                "Before returning, tell me a joke."; // nudging it to say something additionally to json

        StreamingChatLanguageModel model = OpenAiStreamingChatModel.builder()
                .baseUrl(System.getenv("OPENAI_BASE_URL"))
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .organizationId(System.getenv("OPENAI_ORGANIZATION_ID"))
                .modelName(GPT_3_5_TURBO_1106) // supports response_format = 'json_object'
                .responseFormat("json_object")
                .logRequests(true)
                .logResponses(true)
                .build();

        // when
        CompletableFuture<String> futureAnswer = new CompletableFuture<>();
        CompletableFuture<Response<AiMessage>> futureResponse = new CompletableFuture<>();

        model.generate(userMessage, new StreamingResponseHandler<AiMessage>() {

            private final StringBuilder answerBuilder = new StringBuilder();

            @Override
            public void onNext(String token) {
                System.out.println("onNext: '" + token + "'");
                answerBuilder.append(token);
            }

            @Override
            public void onComplete(Response<AiMessage> response) {
                System.out.println("onComplete: '" + response + "'");
                futureAnswer.complete(answerBuilder.toString());
                futureResponse.complete(response);
            }

            @Override
            public void onError(Throwable error) {
                futureAnswer.completeExceptionally(error);
                futureResponse.completeExceptionally(error);
            }
        });

        String json = futureAnswer.get(30, SECONDS);
        Response<AiMessage> response = futureResponse.get(30, SECONDS);

        // then
        assertThat(json).isEqualToIgnoringWhitespace("{\"name\": \"Klaus\", \"surname\": \"Heisler\"}");
        assertThat(response.content().text()).isEqualTo(json);
    }

    @Test
    void should_accept_image_url() {

        // given
        ImageContent imageContent = ImageContent.from(CAT_IMAGE_URL);
        UserMessage userMessage = UserMessage.from(imageContent);

        // when
        TestStreamingResponseHandler<AiMessage> handler = new TestStreamingResponseHandler<>();
        visionModel.generate(singletonList(userMessage), handler);
        Response<AiMessage> response = handler.get();

        // then
        assertThat(response.content().text()).containsIgnoringCase("cat");

        assertThat(response.tokenUsage().inputTokenCount()).isEqualTo(92);
    }

    @Test
    void should_accept_base64_image() {

        // given
        String base64Data = Base64.getEncoder().encodeToString(readBytes(CAT_IMAGE_URL));
        ImageContent imageContent = ImageContent.from(base64Data, "image/png");
        UserMessage userMessage = UserMessage.from(imageContent);

        // when
        TestStreamingResponseHandler<AiMessage> handler = new TestStreamingResponseHandler<>();
        visionModel.generate(singletonList(userMessage), handler);
        Response<AiMessage> response = handler.get();

        // then
        assertThat(response.content().text()).containsIgnoringCase("cat");

        assertThat(response.tokenUsage().inputTokenCount()).isEqualTo(92);
    }

    @Test
    void should_accept_text_and_image() {

        // given
        UserMessage userMessage = UserMessage.from(
                TextContent.from("What do you see? Reply in one word."),
                ImageContent.from(CAT_IMAGE_URL)
        );

        // when
        TestStreamingResponseHandler<AiMessage> handler = new TestStreamingResponseHandler<>();
        visionModel.generate(singletonList(userMessage), handler);
        Response<AiMessage> response = handler.get();

        // then
        assertThat(response.content().text()).containsIgnoringCase("cat");

        assertThat(response.tokenUsage().inputTokenCount()).isEqualTo(102);
    }

    @Test
    void should_accept_text_and_multiple_images() {

        // given
        UserMessage userMessage = UserMessage.from(
                TextContent.from("What do you see? Reply with one word per image."),
                ImageContent.from(CAT_IMAGE_URL),
                ImageContent.from(DICE_IMAGE_URL)
        );

        // when
        TestStreamingResponseHandler<AiMessage> handler = new TestStreamingResponseHandler<>();
        visionModel.generate(singletonList(userMessage), handler);
        Response<AiMessage> response = handler.get();

        // then
        assertThat(response.content().text())
                .containsIgnoringCase("cat")
                .containsIgnoringCase("dice");

        assertThat(response.tokenUsage().inputTokenCount()).isEqualTo(189);
    }

    @Test
    void should_accept_text_and_multiple_images_from_different_sources() {

        // given
        UserMessage userMessage = UserMessage.from(
                ImageContent.from(CAT_IMAGE_URL),
                ImageContent.from(Base64.getEncoder().encodeToString(readBytes(DICE_IMAGE_URL)), "image/png"),
                TextContent.from("What do you see? Reply with one word per image.")
        );

        // when
        TestStreamingResponseHandler<AiMessage> handler = new TestStreamingResponseHandler<>();
        visionModel.generate(singletonList(userMessage), handler);
        Response<AiMessage> response = handler.get();

        // then
        assertThat(response.content().text())
                .containsIgnoringCase("cat")
                .containsIgnoringCase("dice");

        assertThat(response.tokenUsage().inputTokenCount()).isEqualTo(189);
    }

    @Test
    void should_use_enum_as_model_name() {

        // given
        OpenAiStreamingChatModel model = OpenAiStreamingChatModel.builder()
                .baseUrl(System.getenv("OPENAI_BASE_URL"))
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .organizationId(System.getenv("OPENAI_ORGANIZATION_ID"))
                .modelName(GPT_3_5_TURBO)
                .logRequests(true)
                .logResponses(true)
                .build();

        String question = "What is the capital of Germany?";

        // when
        TestStreamingResponseHandler<AiMessage> handler = new TestStreamingResponseHandler<>();
        model.generate(question, handler);
        Response<AiMessage> response = handler.get();

        // then
        assertThat(response.content().text()).containsIgnoringCase("Berlin");
    }

    @Test
    void should_use_default_tokenizer() {

        // when
        int tokenCount = model.estimateTokenCount("Hello, how are you doing?");

        // then
        assertThat(tokenCount).isEqualTo(14);
    }

    @Test
    void should_use_custom_tokenizer() {

        // given

        Tokenizer tokenizer = new Tokenizer() {

            @Override
            public int estimateTokenCountInText(String text) {
                return 42;
            }

            @Override
            public int estimateTokenCountInMessage(ChatMessage message) {
                return 42;
            }

            @Override
            public int estimateTokenCountInMessages(Iterable<ChatMessage> messages) {
                return 42;
            }

            @Override
            public int estimateTokenCountInToolSpecifications(Iterable<ToolSpecification> toolSpecifications) {
                return 42;
            }

            @Override
            public int estimateTokenCountInToolExecutionRequests(Iterable<ToolExecutionRequest> toolExecutionRequests) {
                return 42;
            }
        };

        OpenAiChatModel model = OpenAiChatModel.builder()
                .apiKey("does not matter")
                .tokenizer(tokenizer)
                .build();

        // when
        int tokenCount = model.estimateTokenCount("Hello, how are you doing?");

        // then
        assertThat(tokenCount).isEqualTo(42);
    }

    @Test
    void should_listen_request_and_response() {

        // given
        AtomicReference<ChatModelRequest> requestReference = new AtomicReference<>();
        AtomicReference<ChatModelResponse> responseReference = new AtomicReference<>();

        ChatModelListener listener = new ChatModelListener() {

            @Override
            public void onRequest(ChatModelRequestContext requestContext) {
                requestReference.set(requestContext.request());
                requestContext.attributes().put("id", "12345");
            }

            @Override
            public void onResponse(ChatModelResponseContext responseContext) {
                responseReference.set(responseContext.response());
                assertThat(responseContext.request()).isSameAs(requestReference.get());
                assertThat(responseContext.attributes().get("id")).isEqualTo("12345");
            }

            @Override
            public void onError(ChatModelErrorContext errorContext) {
                fail("onError() must not be called");
            }
        };

        OpenAiChatModelName modelName = GPT_3_5_TURBO;
        double temperature = 0.7;
        double topP = 1.0;
        int maxTokens = 7;

        StreamingChatLanguageModel model = OpenAiStreamingChatModel.builder()
                .baseUrl(System.getenv("OPENAI_BASE_URL"))
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .organizationId(System.getenv("OPENAI_ORGANIZATION_ID"))
                .modelName(modelName)
                .temperature(temperature)
                .topP(topP)
                .maxTokens(maxTokens)
                .logRequests(true)
                .logResponses(true)
                .listeners(singletonList(listener))
                .build();

        UserMessage userMessage = UserMessage.from("hello");

        ToolSpecification toolSpecification = ToolSpecification.builder()
                .name("add")
                .addParameter("a", INTEGER)
                .addParameter("b", INTEGER)
                .build();

        // when
        TestStreamingResponseHandler<AiMessage> handler = new TestStreamingResponseHandler<>();
        model.generate(singletonList(userMessage), singletonList(toolSpecification), handler);
        AiMessage aiMessage = handler.get().content();

        // then
        ChatModelRequest request = requestReference.get();
        assertThat(request.model()).isEqualTo(modelName.toString());
        assertThat(request.temperature()).isEqualTo(temperature);
        assertThat(request.topP()).isEqualTo(topP);
        assertThat(request.maxTokens()).isEqualTo(maxTokens);
        assertThat(request.messages()).containsExactly(userMessage);
        assertThat(request.toolSpecifications()).containsExactly(toolSpecification);

        ChatModelResponse response = responseReference.get();
        assertThat(response.id()).isNotBlank();
        assertThat(response.model()).isNotBlank();
        assertThat(response.tokenUsage().inputTokenCount()).isGreaterThan(0);
        assertThat(response.tokenUsage().outputTokenCount()).isGreaterThan(0);
        assertThat(response.tokenUsage().totalTokenCount()).isGreaterThan(0);
        assertThat(response.finishReason()).isNotNull();
        assertThat(response.aiMessage()).isEqualTo(aiMessage);
    }

    @Test
    void should_listen_error() throws Exception {

        // given
        String wrongApiKey = "banana";

        AtomicReference<ChatModelRequest> requestReference = new AtomicReference<>();
        AtomicReference<Throwable> errorReference = new AtomicReference<>();

        ChatModelListener listener = new ChatModelListener() {

            @Override
            public void onRequest(ChatModelRequestContext requestContext) {
                requestReference.set(requestContext.request());
                requestContext.attributes().put("id", "12345");
            }

            @Override
            public void onResponse(ChatModelResponseContext responseContext) {
                fail("onResponse() must not be called");
            }

            @Override
            public void onError(ChatModelErrorContext errorContext) {
                errorReference.set(errorContext.error());
                assertThat(errorContext.request()).isSameAs(requestReference.get());
                assertThat(errorContext.partialResponse()).isNull(); // can be non-null if it fails in the middle of streaming
                assertThat(errorContext.attributes().get("id")).isEqualTo("12345");
            }
        };

        StreamingChatLanguageModel model = OpenAiStreamingChatModel.builder()
                .apiKey(wrongApiKey)
                .logRequests(true)
                .logResponses(true)
                .listeners(singletonList(listener))
                .build();

        String userMessage = "this message will fail";

        CompletableFuture<Throwable> future = new CompletableFuture<>();
        StreamingResponseHandler<AiMessage> handler = new StreamingResponseHandler<AiMessage>() {

            @Override
            public void onNext(String token) {
                fail("onNext() must not be called");
            }

            @Override
            public void onError(Throwable error) {
                future.complete(error);
            }

            @Override
            public void onComplete(Response<AiMessage> response) {
                fail("onComplete() must not be called");
            }
        };

        // when
        model.generate(userMessage, handler);
        Throwable throwable = future.get(5, SECONDS);

        // then
        assertThat(throwable).isExactlyInstanceOf(OpenAiHttpException.class);
        assertThat(throwable).hasMessageContaining("Incorrect API key provided");

        assertThat(errorReference.get()).isSameAs(throwable);
    }
}