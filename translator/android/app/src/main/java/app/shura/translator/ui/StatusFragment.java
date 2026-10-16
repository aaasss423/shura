package app.shura.translator.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import app.shura.translator.Prefs;
import app.shura.translator.R;
import app.shura.translator.model.Models;
import app.shura.translator.net.ApiClient;
import app.shura.translator.net.ApiException;
import app.shura.translator.net.Cancellation;
import app.shura.translator.net.TranslationApi;

import java.util.List;
import java.util.Locale;

/**
 * Diagnostics: is the server there, which engine is live, and do the knowledge
 * layers have anything for this language.
 *
 * The knowledge counts are read-only. They exist so a reader (or a maintainer) can see
 * that Glossary, Translation Memory and Knowledge are in the path and populated — not
 * so the app can edit them. That logic stays on the server, where it belongs.
 */
public class StatusFragment extends Fragment {

    private ProgressBar progress;
    private TextView health;
    private TextView engine;
    private TextView counts;
    private TextView note;
    private Cancellation cancellation;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_status, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        progress = view.findViewById(R.id.status_progress);
        health = view.findViewById(R.id.status_health);
        engine = view.findViewById(R.id.status_engine);
        counts = view.findViewById(R.id.status_counts);
        note = view.findViewById(R.id.status_note);

        note.setText("Engine selection is the server's decision. No model is chosen by this app.");
        view.findViewById(R.id.status_check).setOnClickListener(v -> check());
    }

    private void check() {
        Prefs prefs = new Prefs(requireContext());
        final TranslationApi api = new TranslationApi(new ApiClient(prefs.baseUrl(), 10000, prefs.timeoutMs()));
        final String language = prefs.sourceLanguage();

        progress.setVisibility(View.VISIBLE);
        health.setText("Checking…");

        cancellation = new Cancellation();
        Async.run(() -> {
            Models.Health result = api.health(cancellation);
            List<Models.Engine> engines = api.engines(cancellation);
            int knowledge = api.countOf("/knowledge", null, cancellation);
            int memory = api.countOf("/memory", null, cancellation);
            int glossary = api.countOf("/glossary", null, cancellation);
            return new Bundle2(result, engines, knowledge, memory, glossary);
        }, new Async.Callback<Bundle2>() {
            @Override
            public void onSuccess(Bundle2 value) {
                progress.setVisibility(View.GONE);
                health.setText(value.health.healthy
                        ? "Server reachable (" + value.health.detail + ")"
                        : "Server answered but is not healthy: " + value.health.detail);
                renderEngines(value.engines);
                renderCounts(value.knowledge, value.memory, value.glossary);
            }

            @Override
            public void onError(Exception error) {
                progress.setVisibility(View.GONE);
                String message = error instanceof ApiException ? error.getMessage() : String.valueOf(error);
                health.setText("Cannot check the server: " + message);
                engine.setText("");
                counts.setText("");
            }
        });
    }

    private void renderEngines(List<Models.Engine> engines) {
        if (engines.isEmpty()) {
            engine.setText(getString(R.string.status_engine) + ": " + getString(R.string.status_unknown));
            return;
        }
        StringBuilder text = new StringBuilder(getString(R.string.status_engine) + ":\n");
        for (Models.Engine item : engines) {
            text.append("  • ").append(item.id);
            if (item.available) {
                text.append(" — available");
            } else {
                text.append(" — unavailable");
                if (!item.reason.isEmpty()) {
                    text.append(" (").append(item.reason).append(")");
                }
            }
            text.append('\n');
        }
        engine.setText(text.toString().trim());
    }

    private void renderCounts(int knowledge, int memory, int glossary) {
        counts.setText(String.format(Locale.ROOT, "%s: %s\n%s: %s\n%s: %s",
                getString(R.string.status_knowledge), format(knowledge),
                getString(R.string.status_memory), format(memory),
                getString(R.string.status_glossary), format(glossary)));
    }

    /** -1 means the endpoint was unreachable or disabled; that is not "zero". */
    private String format(int count) {
        return count < 0 ? "not available" : String.valueOf(count);
    }

    /** Small carrier for the several results the status call gathers. */
    static final class Bundle2 {
        final Models.Health health;
        final List<Models.Engine> engines;
        final int knowledge;
        final int memory;
        final int glossary;

        Bundle2(Models.Health health, List<Models.Engine> engines, int knowledge, int memory, int glossary) {
            this.health = health;
            this.engines = engines;
            this.knowledge = knowledge;
            this.memory = memory;
            this.glossary = glossary;
        }
    }

    @Override
    public void onDestroyView() {
        if (cancellation != null) {
            cancellation.cancel();
        }
        super.onDestroyView();
    }
}
