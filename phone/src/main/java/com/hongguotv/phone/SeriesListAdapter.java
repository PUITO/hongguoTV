package com.hongguotv.phone;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

public final class SeriesListAdapter extends BaseAdapter {
    private final Context ctx;
    private JSONArray data = new JSONArray();

    public SeriesListAdapter(Context ctx) { this.ctx = ctx; }

    public void setData(JSONArray arr) {
        data = arr == null ? new JSONArray() : arr;
        notifyDataSetChanged();
    }

    @Override public int getCount() { return data.length(); }
    @Override public Object getItem(int position) {
        try { return data.getJSONObject(position); } catch (Exception e) { return null; }
    }
    @Override public long getItemId(int position) { return position; }

    @Override public View getView(int position, View convertView, ViewGroup parent) {
        if (convertView == null) {
            convertView = LayoutInflater.from(ctx).inflate(R.layout.item_series, parent, false);
        }
        ImageView cover = convertView.findViewById(R.id.cover);
        TextView title = convertView.findViewById(R.id.title);
        TextView sub = convertView.findViewById(R.id.subtitle);
        try {
            JSONObject o = data.getJSONObject(position);
            title.setText(o.optString("title", "未知"));
            String intro = o.optString("intro", "");
            if (intro.isEmpty()) intro = "ID " + o.optString("seriesId");
            sub.setText(intro);
            ImageLoader.load(cover, o.optString("cover"));
        } catch (Exception e) {
            title.setText("—");
            sub.setText("");
        }
        return convertView;
    }
}
