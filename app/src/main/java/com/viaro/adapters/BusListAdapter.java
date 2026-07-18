package com.viaro.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.example.R;
import com.viaro.listeners.OnBusClickListener;
import com.viaro.models.BusModel;
import java.util.List;

public class BusListAdapter extends RecyclerView.Adapter<BusListAdapter.ViewHolder> {

    private final List<BusModel> busList;
    private final OnBusClickListener listener;

    public BusListAdapter(List<BusModel> busList, OnBusClickListener listener) {
        this.busList = busList;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_bus_list, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        BusModel bus = busList.get(position);
        holder.tvName.setText(bus.getName());
        holder.tvRoute.setText("Route: " + bus.getStartPoint() + " ➔ " + bus.getEndPoint());

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onBusClick(bus);
            }
        });
    }

    @Override
    public int getItemCount() {
        return busList.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        public final TextView tvName;
        public final TextView tvRoute;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvName = itemView.findViewById(R.id.tv_item_bus_name);
            tvRoute = itemView.findViewById(R.id.tv_item_bus_route);
        }
    }
}
