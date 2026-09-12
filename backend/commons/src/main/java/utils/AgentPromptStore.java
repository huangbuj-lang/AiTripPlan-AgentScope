package utils;

import com.alibaba.nacos.api.ai.listener.AbstractNacosPromptListener;
import com.alibaba.nacos.api.ai.listener.NacosPromptEvent;
import com.alibaba.nacos.api.ai.model.prompt.Prompt;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Subscribes named prompts from Nacos Prompt management. */
@Slf4j
public final class AgentPromptStore {

    private static final Map<String, String> PROMPTS = new ConcurrentHashMap<>();
    private static final Map<String, AbstractNacosPromptListener> LISTENERS = new ConcurrentHashMap<>();
    private static final AtomicBoolean STARTED = new AtomicBoolean();

    private AgentPromptStore() {}

    public static void start() {
        if (!STARTED.compareAndSet(false, true)) return;
        Set<String> names = Set.copyOf(AgentPrompts.defaults().keySet());
        Thread thread = new Thread(() -> connectAndSubscribe(names), "nacos-agent-prompt-loader");
        thread.setDaemon(true);
        thread.start();
    }

    public static boolean loaded() {
        return !PROMPTS.isEmpty();
    }

    public static boolean customized(String name) {
        String value = PROMPTS.get(promptKey(name));
        return value != null && !value.isBlank();
    }

    public static String source() {
        return PROMPTS.isEmpty() ? "default" : "nacos-prompt";
    }

    public static String promptKey(String name) {
        return Config.get("NACOS_PROMPT_PREFIX", "aitripplan.") + name;
    }

    public static String prompt(String name, String defaultValue) {
        String value = PROMPTS.get(promptKey(name));
        return value == null || value.isBlank() ? defaultValue : value;
    }

    static void applyPrompt(String name, String template) {
        if (name == null) return;
        if (template == null || template.isBlank()) {
            PROMPTS.remove(promptKey(name));
        } else {
            PROMPTS.put(promptKey(name), template);
        }
    }

    static void resetForTest() {
        PROMPTS.clear();
    }

    private static void connectAndSubscribe(Set<String> names) {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                subscribeAll(names);
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("Nacos prompt service unavailable, retrying in 15s: {}", e.getMessage());
                sleepQuietly();
            }
        }
    }

    private static void subscribeAll(Set<String> names) throws Exception {
        var service = utils.NacosUtil.getNacosClient();
        boolean failed = false;

        for (String name : names) {
            if (LISTENERS.containsKey(name)) continue;
            try {
                AbstractNacosPromptListener listener = new PromptListener(name);
                Prompt prompt = service.subscribePrompt(promptKey(name), null, null, listener);
                apply(name, prompt);
                LISTENERS.put(name, listener);
                log.info("Subscribed Nacos prompt: {}", promptKey(name));
            } catch (Exception e) {
                failed = true;
                log.warn("Unable to subscribe Nacos prompt {}: {}", promptKey(name), e.getMessage());
            }
        }

        if (failed && LISTENERS.size() < names.size()) {
            sleepQuietly();
            throw new IllegalStateException("Some Nacos prompts are still unsubscribed");
        }
    }

    private static void apply(String name, Prompt prompt) {
        applyPrompt(name, prompt == null ? null : prompt.getTemplate());
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(15_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

private static class PromptListener extends AbstractNacosPromptListener {
        private final String name;

        private PromptListener(String name) {
            this.name = name;
        }

        @Override
        public void onEvent(NacosPromptEvent event) {
            try {
                applyPrompt(name, event.getPrompt() == null ? null : event.getPrompt().getTemplate());
            } catch (Exception e) {
                log.warn("Invalid Nacos prompt {}: {}", promptKey(name), e.getMessage());
            }
        }
    }
}