package com.hongguotv.phone;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

public final class EpisodeAdapter extends BaseAdapter {
    private final Context ctx;
    private JSONArray data = new JSONArray();
    private int selected = 0;

    public EpisodeAdapter(Context ctx) { this.ctx = ctx; }

    public void setData(JSONArray arr) {
        data = arr == null ? new JSONArray() : arr;
        if (selected >= data.length()) selected = 0;
        notifyDataSetChanged();
    }

    public void setSelected(int index) {
        selected = index;
        notifyDataSetChanged();
    }

    public int getSelected() { return selected; }

    @Override public int getCount() { return data.length(); }
    @Override public Object getItem(int position) {
        try { return data.getJSONObject(position); } catch (Exception e) { return null; }
    }
    @Override public long getItemId(int position) { return position; }

    @Override public View getView(int position, View convertView, ViewGroup parent) {
        if (convertView == null) {
            convertView = LayoutInflater.from(ctx).inflate(R.layout.item_episode, parent, false);
        }
        TextView tv = convertView.findViewById(R.id.epText);
        try {
            JSONObject o = data.getJSONObject(position);
            boolean locked = o.optBoolean("locked", false);
            String label = String.valueOf(o.optInt("episode", position + 1));
            if (locked) label = label + "锁";
            tv.setText(label);
            if (position == selected) {
                tv.setBackgroundResource(R.drawable.bg_ep_selected);
                tv.setTextColor(0xFFFFFFFF);
            } else if (locked) {
                tv.setBackgroundResource(R.drawable.bg_ep_locked);
                tv.setTextColor(0xFF999999);
            } else {
                tv.setBackgroundResource(R.drawable.bg_ep_normal);
                tv.setTextColor(0xFF333333);
            }
        } catch (Exception e) {
            tv.setText("—");
        }
        return convertView;
    }
}
