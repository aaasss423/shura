package app.shura.translator.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

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
import app.shura.translator.state.RequestState;
import app.shura.translator.util.ArabicText;

import java.util.Locale;

/**
 * The single-text screen.
 *
 * The whole loading / error / retry / cancel contract is driven from one
 * {@link RequestState}, so the UI cannot end up showing a spinner over a result, or
 * a Retry button for a request that will never succeed.
 */
public class TranslateFragment extends Fragment {

    private final RequestState<Models.Translation> state = new RequestState<>();
    private Cancellation cancellation;

    private EditText input;
    private TextView output;
    private TextView error;
    private TextView meta;
    private ProgressBar progress;
    private Button translate;
    private Button cancel;
    private Button retry;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_translate, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        input = view.findViewById(R.id.input);
        output = view.findViewById(R.id.output);
        error = view.findViewById(R.id.error);
        meta = view.findViewById(R.id.meta);
        progress = view.findViewById(R.id.progress);
        translate = view.findViewById(R.id.translate);
        cancel = view.findViewById(R.id.cancel);
        retry = view.findViewById(R.id.retry);

        translate.setOnClickListener(v -> submit(false));
        retry.setOnClickListener(v -> submit(true));
        cancel.setOnClickListener(v -> onCancel());
        view.findViewById(R.id.clear).setOnClickListener(v -> clearAll());

        output.setText(R.string.label_nothing_yet);
        render();
    }

    private void submit(boolean isRetry) {
        final String text = input.getText().toString().trim();
        if (text.isEmpty()) {
            // Caught before any request: the server would answer 400, and showing a
            // server error for an empty box would be blaming the wrong thing.
            state.fail(getString(R.string.hint_input) + " is required", false);
            error.setText(text.isEmpty() ? "Enter some text to translate." : "");
            render();
            return;
        }

        Prefs prefs = new Prefs(requireContext());
        final TranslationApi api = new TranslationApi(new ApiClient(
                prefs.baseUrl(), 10000, prefs.timeoutMs()));

        final TranslationApi.TranslateRequest request = new TranslationApi.TranslateRequest();
        request.text = text;
        request.sourceLanguage = prefs.sourceLanguage();
        request.targetLanguage = Prefs.TARGET_LANGUAGE;
        request.engine = prefs.engine();
        request.timeoutMs = prefs.timeoutMs();

        cancellation = new Cancellation();
        state.start();
        render();

        Async.run(() -> api.translate(request, cancellation), new Async.Callback<Models.Translation>() {
            @Override
            public void onSuccess(Models.Translation value) {
                state.succeed(value, value.elapsedMs);
                showResult(value, isRetry);
                render();
            }

            @Override
            public void onError(Exception error) {
                onFailure(error);
            }
        });
    }

    private void showResult(Models.Translation result, boolean wasRetry) {
        output.setText(result.text.isEmpty() ? getString(R.string.label_nothing_yet) : result.text);
        applyDirection(result.direction, result.text);

        StringBuilder details = new StringBuilder();
        details.append(String.format(Locale.ROOT, "engine=%s · %dms", result.engine, result.elapsedMs));
        if (result.fromCache) {
            // From cache means a memory hit or glossary application upstream; either way
            // the reader should know this was not a fresh model call.
            details.append(" · from memory/cache");
        }
        if (!result.detectedLanguage.isEmpty()) {
            details.append(" · detected=").append(result.detectedLanguage);
        }
        int arabicChars = ArabicText.arabicCharacterCount(result.text);
        if (!ArabicText.containsArabic(result.text) && !result.text.isEmpty()) {
            details.append("\nwarning: the output contains no Arabic script");
        } else if (arabicChars > 0) {
            details.append(" · ").append(arabicChars).append(" Arabic character(s)");
        }
        if (ArabicText.looksUnusable(result.text)) {
            details.append("\nwarning: this output looks unusable");
        }
        if (wasRetry && state.attempt() > 1) {
            details.append("\n(retry #").append(state.attempt() - 1).append(")");
        }
        meta.setText(details.toString());
    }

    private void onFailure(Exception thrown) {
        if (thrown instanceof ApiException && ((ApiException) thrown).isCancelled()) {
            state.cancel();
        } else if (thrown instanceof ApiException) {
            ApiException api = (ApiException) thrown;
            String prefix = api.status() > 0 ? "HTTP " + api.status() + ": " : "";
            state.fail(prefix + api.getMessage(), api.retryable());
        } else {
            state.fail(thrown.getMessage() == null ? thrown.toString() : thrown.getMessage(), true);
        }
        render();
    }

    private void onCancel() {
        if (cancellation != null) {
            cancellation.cancel();
        }
        state.cancel();
        render();
    }

    private void clearAll() {
        input.setText("");
        output.setText(R.string.label_nothing_yet);
        output.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        meta.setText("");
        state.reset();
        render();
    }

    /**
     * Applies the server's direction rather than guessing.
     *
     * An Arabic line that is mostly Latin names is common in translated manga; deciding
     * from the text would lay it out wrong.
     */
    private void applyDirection(String direction, String text) {
        boolean rtl = ArabicText.isRtl(direction, text);
        output.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        output.setTextDirection(rtl ? View.TEXT_DIRECTION_RTL : View.TEXT_DIRECTION_LOCALE);
    }

    /** One render from state, so the widgets cannot disagree with each other. */
    private void render() {
        boolean busy = state.isBusy();
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        translate.setEnabled(!busy);
        cancel.setEnabled(busy);

        boolean failed = state.phase() == RequestState.Phase.FAILED;
        error.setVisibility(failed ? View.VISIBLE : View.GONE);
        error.setText(state.errorMessage());

        boolean cancelled = state.phase() == RequestState.Phase.CANCELLED;
        if (cancelled) {
            meta.setText("Cancelled. Press Retry to try again.");
        }

        boolean showRetry = (failed && state.retryable()) || cancelled;
        retry.setVisibility(showRetry ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onDestroyView() {
        // Cancel on teardown so a rotation does not leave a request holding a socket.
        if (cancellation != null) {
            cancellation.cancel();
        }
        super.onDestroyView();
    }

    /** Exposed for the toolbar's copy action. */
    public void copyOutput() {
        String text = output.getText().toString();
        if (text.isEmpty() || getString(R.string.label_nothing_yet).equals(text)) {
            Toast.makeText(getContext(), "Nothing to copy", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) requireContext()
                .getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("translation", text));
            Toast.makeText(getContext(), "Copied", Toast.LENGTH_SHORT).show();
        }
    }
}
