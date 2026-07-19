package com.viaro.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.vineyard.viaro.app.R;
import com.viaro.listeners.OnNavClickListener;
import com.viaro.models.HorizontalNavItem;
import java.util.List;

public class HorizontalNavAdapter extends RecyclerView.Adapter<HorizontalNavAdapter.ViewHolder> {

    private final List<HorizontalNavItem> items;
    private final OnNavClickListener listener;

    public HorizontalNavAdapter(List<HorizontalNavItem> items, OnNavClickListener listener) {
        this.items = items;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_nav_menu, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        HorizontalNavItem item = items.get(position);
        holder.tvLabel.setText(item.getLabel());
        holder.ivIcon.setImageResource(item.getIconResId());
        
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onNavClick(item);
            }
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        public final ImageView ivIcon;
        public final TextView tvLabel;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            ivIcon = itemView.findViewById(R.id.iv_nav_icon);
            tvLabel = itemView.findViewById(R.id.tv_nav_label);
        }
    }
}
