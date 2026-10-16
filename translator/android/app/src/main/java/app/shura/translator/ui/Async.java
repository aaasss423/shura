package app.shura.translator.ui;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One background thread for network work, and one thread for delivering results.
 *
 * A single worker is deliberate. The server already applies concurrency control and
 * rate limits; an app that fans out twenty parallel requests would defeat both and
 * then present the resulting errors to the reader as if the model were broken.
 */
public final class Async {

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(new ThreadFactory() {
        private final AtomicInteger counter = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "shura-io-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    });

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Async() {
    }

    public interface Work<T> {
        T run() throws Exception;
    }

    public interface Callback<T> {
        void onSuccess(T value);

        void onError(Exception error);
    }

    /** Runs work off the main thread and delivers the outcome back on it. */
    public static <T> void run(Work<T> work, Callback<T> callback) {
        WORKER.execute(() -> {
            try {
                T value = work.run();
                MAIN.post(() -> callback.onSuccess(value));
            } catch (Exception error) {
                MAIN.post(() -> callback.onError(error));
            }
        });
    }
}
