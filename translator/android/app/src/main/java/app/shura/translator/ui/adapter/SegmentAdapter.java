package app.shura.translator.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import app.shura.translator.R;
import app.shura.translator.model.Models;

import java.util.ArrayList;
import java.util.List;

/**
 * Shows source/target pairs one balloon at a time.
 *
 * Per-segment rows rather than one blob: a manga page is a sequence of short lines
 * and the reader needs to match each translation to the balloon it belongs to.
 */
public class SegmentAdapter extends RecyclerView.Adapter<SegmentAdapter.Holder> {

    private final List<Models.Segment> segments = new ArrayList<>();

    public void setSegments(List<Models.Segment> values) {
        segments.clear();
        if (values != null) {
            segments.addAll(values);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_segment, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        Models.Segment segment = segments.get(position);
        holder.index.setText("#" + (position + 1));
        holder.source.setText(segment.source);
        if (segment.isFailed()) {
            // The failure is shown, not hidden: a silently dropped balloon reads as
            // "there was nothing here", which is worse than an honest gap.
            holder.target.setText(R.string.chapter_degraded);
            holder.target.setLayoutDirection(View.LAYOUT_DIRECTION_LOCALE);
        } else {
            holder.target.setText(segment.target);
            // Arabic is RTL; the server's per-chapter direction applies to the whole page.
            holder.target.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        }
    }

    @Override
    public int getItemCount() {
        return segments.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final TextView index;
        final TextView source;
        final TextView target;

        Holder(@NonNull View itemView) {
            super(itemView);
            index = itemView.findViewById(R.id.segment_index);
            source = itemView.findViewById(R.id.segment_source);
            target = itemView.findViewById(R.id.segment_target);
        }
    }
}
