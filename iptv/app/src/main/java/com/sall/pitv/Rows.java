package com.sall.pitv;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/** Netflix-style rows of posters: one row per category, each scrolling sideways. */
final class Rows extends RecyclerView {
    static final int POSTER = 0, WIDE = 1, CHANNEL = 2;

    static final class Row {
        final String title;
        final int style;
        /** Model.Item, or Library.Progress for "continue watching" */
        final List<Object> items;

        Row(String title, int style, List<?> items) {
            this.title = title;
            this.style = style;
            this.items = new ArrayList<>(items);
        }
    }

    interface Callbacks {
        void onFocus(Object item);

        void onClick(Object item);

        /** Pause (or Menu) on a card: favourite it, for instance */
        default void onMenu(Object item) {
        }
    }

    private final List<Row> rows = new ArrayList<>();
    private final Callbacks cb;
    private final RecycledViewPool pool = new RecycledViewPool();
    private final int pad;
    /** a series' page: episode cards show their own title and still, not the series' */
    boolean episodeCards;

    Rows(Context c, Callbacks cb) {
        super(c);
        this.cb = cb;
        pad = Ui.dp(c, 48);
        setLayoutManager(new Ui.PinnedLayout(c, VERTICAL, 0));
        setClipToPadding(false);
        setClipChildren(false);
        setPadding(0, 0, 0, Ui.dp(c, 200));
        setItemAnimator(null);
        setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);
        pool.setMaxRecycledViews(POSTER, 40);
        pool.setMaxRecycledViews(WIDE, 24);
        pool.setMaxRecycledViews(CHANNEL, 24);
        setAdapter(new RowAdapter());
    }

    void setRows(List<Row> r) {
        rows.clear();
        for (Row x : r) if (!x.items.isEmpty()) rows.add(x);
        getAdapter().notifyDataSetChanged();
    }

    boolean isEmpty() {
        return rows.isEmpty();
    }

    /** puts the highlight on a card of the first row */
    void focusAt(int index) {
        scrollToPosition(0);
        post(() -> {
            ViewHolder h = findViewHolderForAdapterPosition(0);
            if (!(h instanceof RowHolder)) return;
            RecyclerView list = ((RowHolder) h).list;
            list.scrollToPosition(index);
            list.post(() -> {
                View v = list.getLayoutManager().findViewByPosition(index);
                if (v != null) v.requestFocus();
            });
        });
    }

    /** puts the highlight on the first card */
    void focusFirst() {
        scrollToPosition(0);
        post(() -> {
            ViewHolder h = findViewHolderForAdapterPosition(0);
            if (h instanceof RowHolder) {
                RecyclerView list = ((RowHolder) h).list;
                View first = list.getLayoutManager().findViewByPosition(0);
                if (first != null) first.requestFocus();
                else list.requestFocus();
            } else requestFocus();
        });
    }

    private final class RowHolder extends ViewHolder {
        final TextView title;
        final RecyclerView list;
        final CardAdapter cards = new CardAdapter();

        RowHolder(LinearLayout v, TextView title, RecyclerView list) {
            super(v);
            this.title = title;
            this.list = list;
        }
    }

    private final class RowAdapter extends Adapter<RowHolder> {
        @NonNull
        @Override
        public RowHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            Context c = parent.getContext();
            LinearLayout v = new LinearLayout(c);
            v.setOrientation(LinearLayout.VERTICAL);
            v.setClipChildren(false);
            v.setClipToPadding(false);
            TextView t = Ui.line(c, 15, Color.WHITE, true);
            t.setPadding(pad, Ui.dp(c, 6), pad, Ui.dp(c, 6));
            v.addView(t);
            RecyclerView list = new RecyclerView(c);
            list.setLayoutManager(new Ui.PinnedLayout(c, HORIZONTAL, 0));
            list.setRecycledViewPool(pool);
            list.setClipToPadding(false);
            list.setClipChildren(false);
            list.setPadding(pad, Ui.dp(c, 8), pad, Ui.dp(c, 14));
            list.setItemAnimator(null);
            list.setHasFixedSize(true);
            v.addView(list, new LinearLayout.LayoutParams(-1, -2));
            v.setLayoutParams(new LayoutParams(-1, -2));
            RowHolder h = new RowHolder(v, t, list);
            list.setAdapter(h.cards);
            return h;
        }

        @Override
        public void onBindViewHolder(@NonNull RowHolder h, int pos) {
            Row r = rows.get(pos);
            h.title.setText(r.title);
            h.cards.row = r;
            h.cards.notifyDataSetChanged();
            h.list.scrollToPosition(0);
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }
    }

    private static final class CardHolder extends ViewHolder {
        final ImageView image;
        final TextView name, sub;
        final ProgressBar bar;
        /** the grow-and-frame effect, which the per-item listener calls first */
        final View.OnFocusChangeListener grow;

        CardHolder(View v, ImageView image, TextView name, TextView sub, ProgressBar bar) {
            super(v);
            grow = v.getOnFocusChangeListener();
            this.image = image;
            this.name = name;
            this.sub = sub;
            this.bar = bar;
        }
    }

    private final class CardAdapter extends Adapter<CardHolder> {
        Row row;

        @Override
        public int getItemViewType(int pos) {
            return row.style;
        }

        @NonNull
        @Override
        public CardHolder onCreateViewHolder(@NonNull ViewGroup parent, int style) {
            return card(parent.getContext(), style);
        }

        @Override
        public void onBindViewHolder(@NonNull CardHolder h, int pos) {
            Object o = row.items.get(pos);
            bind(h, o, row.style);
            h.itemView.setOnClickListener(v -> cb.onClick(o));
            h.itemView.setOnKeyListener((v, key, e) -> {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || key == KeyEvent.KEYCODE_MEDIA_PAUSE || key == KeyEvent.KEYCODE_MENU) {
                    cb.onMenu(o);
                    return true;
                }
                return false;
            });
            h.itemView.setOnFocusChangeListener((v, has) -> {
                h.grow.onFocusChange(v, has);
                if (has) cb.onFocus(o);
            });
        }

        @Override
        public int getItemCount() {
            return row == null ? 0 : row.items.size();
        }
    }

    // ---------------------------------------------------------------- cards

    private CardHolder card(Context c, int style) {
        int w, h;
        if (style == POSTER) {
            w = Ui.dp(c, 104);
            h = Ui.dp(c, 156);
        } else {
            w = Ui.dp(c, style == CHANNEL ? 150 : 196);
            h = w * 9 / 16;
        }
        FrameLayout f = new FrameLayout(c);
        f.setFocusable(true);
        f.setClickable(true);
        f.setBackground(Ui.rounded(Ui.CARD, Ui.dp(c, 6)));
        f.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), Ui.dp(c, 6));
            }
        });
        f.setClipToOutline(true);
        RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(w, h);
        lp.rightMargin = Ui.dp(c, 10);
        f.setLayoutParams(lp);

        // the title, seen until the poster arrives or when there is none
        TextView name = Ui.text(c, style == POSTER ? 13 : 12, Color.WHITE, true);
        name.setGravity(style == POSTER ? Gravity.CENTER : Gravity.START | Gravity.BOTTOM);
        name.setMaxLines(style == POSTER ? 4 : 2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        int p = Ui.dp(c, 8);
        name.setPadding(p, p, p, style == POSTER ? p : Ui.dp(c, 10));

        ImageView img = new ImageView(c);
        TextView sub = null;
        ProgressBar bar = null;
        if (style == POSTER) {
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            f.addView(name, new FrameLayout.LayoutParams(-1, -1));
            f.addView(img, new FrameLayout.LayoutParams(-1, -1));
        } else if (style == CHANNEL) {
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(-1, -1);
            ip.setMargins(Ui.dp(c, 24), Ui.dp(c, 12), Ui.dp(c, 24), Ui.dp(c, 26));
            f.addView(img, ip);
            name.setTextSize(11);
            name.setMaxLines(1);
            name.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
            name.setPadding(Ui.dp(c, 6), 0, Ui.dp(c, 6), Ui.dp(c, 6));
            f.addView(name, new FrameLayout.LayoutParams(-1, -1));
        } else {
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            f.addView(img, new FrameLayout.LayoutParams(-1, -1));
            View shade = new View(c);
            shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0x00000000, 0x22000000, 0xDD000000}));
            f.addView(shade, new FrameLayout.LayoutParams(-1, -1));
            f.addView(name, new FrameLayout.LayoutParams(-1, -1));
            sub = Ui.line(c, 10, Ui.DIM, false);
            sub.setPadding(p, p, p, p);
            f.addView(sub, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));
            bar = new ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(1000);
            bar.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.RED));
            bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x66FFFFFF));
            FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-1, Ui.dp(c, 3), Gravity.BOTTOM);
            f.addView(bar, bp);
        }
        Ui.focusCard(f, style == POSTER ? 1.1f : 1.07f);
        return new CardHolder(f, img, name, sub, bar);
    }

    private void bind(CardHolder h, Object o, int style) {
        Context c = getContext();
        Model.Item i = o instanceof Library.Progress ? ((Library.Progress) o).item : (Model.Item) o;
        h.name.setText(i.kind == Model.EPISODE && i.seriesName != null && !episodeCards ? i.seriesName : i.name);
        if (style == POSTER) {
            Images.get(c).load(h.image, i.image, Ui.dp(c, 104), Ui.dp(c, 156), false);
        } else if (style == CHANNEL) {
            Images.get(c).load(h.image, i.image, Ui.dp(c, 110), Ui.dp(c, 60), true);
        } else {
            boolean still = i.kind == Model.LIVE || (i.kind == Model.EPISODE && i.image != null && !i.image.isEmpty());
            String img = still ? i.image : i.backdrop != null && !i.backdrop.isEmpty() ? i.backdrop : i.image;
            Images.get(c).load(h.image, img, Ui.dp(c, 196), Ui.dp(c, 110), false);
            if (h.sub != null) {
                h.sub.setText(i.kind == Model.EPISODE ? (episodeCards ? "Épisode " + i.episode : "S" + i.season + " · É" + i.episode) : "");
                h.sub.setVisibility(i.kind == Model.EPISODE ? View.VISIBLE : View.GONE);
            }
            if (h.bar != null) {
                Library.Progress p = o instanceof Library.Progress ? (Library.Progress) o : null;
                h.bar.setVisibility(p != null && p.dur > 0 ? View.VISIBLE : View.GONE);
                if (p != null && p.dur > 0) h.bar.setProgress((int) (1000 * p.pos / p.dur));
            }
        }
    }
}
