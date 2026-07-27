package dtm.ide.debug;

import dtm.ide.api.extension.event.BreakpointChangedEvent;
import dtm.ide.api.extension.runconfig.RunConfigurationData;
import dtm.ide.api.extension.runconfig.RunExecutionContext;
import dtm.ide.api.extension.runconfig.RunProcessHandle;
import dtm.ide.lsp.PhpToolchainService;
import dtm.ide.run.PhpRunSupport;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class PhpDebugService {
    private volatile PhpDapSession session;
    private volatile RunProcessHandle debuggee;

    public RunProcessHandle launch(List<String> adapterCommand,
                                   RunConfigurationData configuration,
                                   RunExecutionContext context,
                                   Path root,
                                   PhpRunSupport runSupport,
                                   PhpToolchainService toolchain,
                                   int port,
                                   PhpDapSession.Listener listener) throws Exception {
        stop();
        PipedInputStream output = new PipedInputStream(256 * 1024);
        PipedOutputStream console = new PipedOutputStream(output);
        PhpDapSession created = new PhpDapSession(adapterCommand, root, port,
                context == null ? List.of() : context.getBreakpoints(), console, listener);
        created.start();
        session = created;

        if (!PhpRunSupport.TYPE_XDEBUG_LISTEN.equals(configuration.getType())) {
            debuggee = runSupport.launch(configuration, root, toolchain, true);
            pump(debuggee.getOutput(), console);
        }
        AtomicBoolean terminated = new AtomicBoolean();
        return RunProcessHandle.builder()
                .output(output)
                .input(debuggee == null ? null : debuggee.getInput())
                .alive(() -> !terminated.get() && created.isAlive())
                .terminate(() -> {
                    if (terminated.compareAndSet(false, true)) stop();
                })
                .readonly(debuggee == null || debuggee.isReadonly())
                .processPid(() -> debuggee == null ? 0L : debuggee.getProcessPid())
                .build();
    }

    public void breakpointChanged(BreakpointChangedEvent event) {
        PhpDapSession current = session;
        if (current != null) current.applyBreakpoint(event);
    }

    public void command(String command) {
        PhpDapSession current = session;
        if (current == null) return;
        switch (command) {
            case "continue" -> current.resume();
            case "next" -> current.next();
            case "stepIn" -> current.stepIn();
            case "stepOut" -> current.stepOut();
            case "pause" -> current.pause();
            default -> {
            }
        }
    }

    public boolean active() {
        PhpDapSession current = session;
        return current != null && current.isAlive();
    }

    public void stop() {
        PhpDapSession current = session;
        session = null;
        if (current != null) current.terminate();
        RunProcessHandle process = debuggee;
        debuggee = null;
        if (process != null && process.isAlive()) process.terminate();
    }

    private static void pump(java.io.InputStream input, PipedOutputStream output) {
        Thread thread = new Thread(() -> {
            try (input) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    synchronized (output) {
                        output.write(buffer, 0, count);
                        output.flush();
                    }
                }
            } catch (IOException ignored) {
            }
        }, "php-debuggee-output");
        thread.setDaemon(true);
        thread.start();
    }
}
