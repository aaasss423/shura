package app.shura.translator.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import app.shura.translator.Prefs;
import app.shura.translator.R;
import app.shura.translator.net.ApiClient;

/**
 * Settings: where the server is, and which source language to send.
 *
 * The engine field is deliberately optional and defaults to empty, meaning "let the
 * server route". The app does not choose a model: primary, fallback and per-language
 * routing are all undecided until real measurements exist, and a client that hardcoded
 * a preference would bake that guess into every build.
 */
public class SettingsFragment extends Fragment {

    private EditText serverUrl;
    private EditText engine;
    private EditText source;
    private EditText timeout;
    private TextView result;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        Prefs prefs = new Prefs(requireContext());
        serverUrl = view.findViewById(R.id.server_url);
        engine = view.findViewById(R.id.engine_id);
        source = view.findViewById(R.id.source_language);
        timeout = view.findViewById(R.id.timeout_ms);
        result = view.findViewById(R.id.settings_result);

        serverUrl.setText(prefs.baseUrl());
        engine.setText(prefs.engine());
        source.setText(prefs.sourceLanguage());
        timeout.setText(String.valueOf(prefs.timeoutMs()));

        view.findViewById(R.id.save).setOnClickListener(v -> save());
    }

    private void save() {
        String url = serverUrl.getText().toString().trim();
        String normalized = ApiClient.normalizeBase(url);

        if (!normalized.isEmpty() && !normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            // Caught here rather than as a confusing network error later.
            result.setText("The address must start with http:// or https://");
            return;
        }

        int timeoutMs;
        try {
            timeoutMs = Integer.parseInt(timeout.getText().toString().trim());
            if (timeoutMs < 1000) {
                result.setText("The timeout must be at least 1000 ms");
                return;
            }
        } catch (NumberFormatException error) {
            result.setText("The timeout must be a number of milliseconds");
            return;
        }

        Prefs prefs = new Prefs(requireContext());
        prefs.setBaseUrl(normalized);
        prefs.setEngine(engine.getText().toString().trim());
        prefs.setSourceLanguage(source.getText().toString().trim());
        prefs.setTimeoutMs(timeoutMs);

        result.setText(getString(R.string.settings_saved) + " " + normalized);
    }
}
