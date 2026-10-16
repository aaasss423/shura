package app.shura.translator.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import app.shura.translator.Prefs;
import app.shura.translator.R;
import app.shura.translator.model.Models;
import app.shura.translator.net.ApiClient;
import app.shura.translator.net.ApiException;
import app.shura.translator.net.Cancellation;
import app.shura.translator.net.TranslationApi;
import app.shura.translator.state.RequestState;
import app.shura.translator.ui.adapter.SegmentAdapter;
import app.shura.translator.util.SegmentParser;

import java.util.List;

/**
 * The chapter screen: many segments, one request.
 *
 * Sends the whole page through `/translate/chapter` with `failurePolicy: partial`, so
 * a handful of bad balloons leave the page readable instead of failing it entirely.
 * Whether that happened is reported, not smoothed over.
 */
public class ChapterFragment extends Fragment {

    private final RequestState<Models.Chapter> state = new RequestState<>();
    private final SegmentAdapter adapter = new SegmentAdapter();
    private Cancellation cancellation;

    private EditText chapterInput;
    private TextView info;
    private TextView errorView;
    private ProgressBar progress;
    private Button translateButton;
    private Button cancelButton;
    private Button retryButton;
    private RecyclerView results;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_chapter, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        chapterInput = view.findViewById(R.id.chapter_input);
        info = view.findViewById(R.id.chapter_info);
        errorView = view.findViewById(R.id.chapter_error);
        progress = view.findViewById(R.id.chapter_progress);
        translateButton = view.findViewById(R.id.chapter_translate);
        cancelButton = view.findViewById(R.id.chapter_cancel);
        retryButton = view.findViewById(R.id.chapter_retry);
        results = view.findViewById(R.id.chapter_results);

        results.setLayoutManager(new LinearLayoutManager(getContext()));
        results.setAdapter(adapter);

        translateButton.setOnClickListener(v -> submit());
        retryButton.setOnClickListener(v -> submit());
        cancelButton.setOnClickListener(v -> {
            if (cancellation != null) {
                cancellation.cancel();
            }
            state.cancel();
            render();
        });

        updateSegmentCount();
        chapterInput.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(android.text.Editable text) {
                updateSegmentCount();
            }
        });
        render();
    }

    private void updateSegmentCount() {
        List<String> segments = SegmentParser.parse(chapterInput.getText().toString());
        info.setText(getString(R.string.chapter_ready,
                segments.size(), SegmentParser.countCharacters(segments)));
    }

    private void submit() {
        final List<String> segments = SegmentParser.parse(chapterInput.getText().toString());
        if (segments.isEmpty()) {
            state.fail("Paste some chapter text first. One line per balloon.", false);
            render();
            return;
        }

        Prefs prefs = new Prefs(requireContext());
        final TranslationApi api = new TranslationApi(new ApiClient(prefs.baseUrl(), 10000, prefs.timeoutMs()));

        final TranslationApi.ChapterRequest request = new TranslationApi.ChapterRequest();
        request.segments = segments;
        request.sourceLanguage = prefs.sourceLanguage();
        request.targetLanguage = Prefs.TARGET_LANGUAGE;
        request.engine = prefs.engine();
        request.failurePolicy = "partial";
        request.deadlineMs = prefs.timeoutMs();

        cancellation = new Cancellation();
        state.start();
        render();

        Async.run(() -> api.translateChapter(request, cancellation), new Async.Callback<Models.Chapter>() {
            @Override
            public void onSuccess(Models.Chapter value) {
                state.succeed(value, value.elapsedMs);
                adapter.setSegments(value.segments);
                info.setText(getString(R.string.chapter_ready, segments.size(),
                                SegmentParser.countCharacters(segments))
                        + String.format(java.util.Locale.ROOT, " · engine=%s · %dms", value.engine, value.elapsedMs));
                if (value.degraded) {
                    errorView.setVisibility(View.VISIBLE);
                    errorView.setText(getString(R.string.chapter_degraded));
                }
                render();
            }

            @Override
            public void onError(Exception error) {
                if (error instanceof ApiException && ((ApiException) error).isCancelled()) {
                    state.cancel();
                } else if (error instanceof ApiException) {
                    ApiException api = (ApiException) error;
                    state.fail((api.status() > 0 ? "HTTP " + api.status() + ": " : "") + api.getMessage(),
                            api.retryable());
                } else {
                    state.fail(String.valueOf(error.getMessage()), true);
                }
                render();
            }
        });
    }

    private void render() {
        boolean busy = state.isBusy();
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        translateButton.setEnabled(!busy);
        cancelButton.setEnabled(busy);

        boolean failed = state.phase() == RequestState.Phase.FAILED;
        if (failed) {
            errorView.setVisibility(View.VISIBLE);
            errorView.setText(state.errorMessage());
        } else if (state.phase() != RequestState.Phase.SUCCEEDED) {
            errorView.setVisibility(View.GONE);
        }

        boolean cancelled = state.phase() == RequestState.Phase.CANCELLED;
        if (cancelled) {
            errorView.setVisibility(View.VISIBLE);
            errorView.setText("Cancelled. Press Retry to translate the page again.");
        }

        retryButton.setVisibility(((failed && state.retryable()) || cancelled) ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onDestroyView() {
        if (cancellation != null) {
            cancellation.cancel();
        }
        super.onDestroyView();
    }
}
