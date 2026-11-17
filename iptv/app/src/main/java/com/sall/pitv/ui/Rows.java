package com.sall.pitv.ui;

import com.sall.pitv.R;
import com.sall.pitv.player.*;
import com.sall.pitv.iptv.*;
import com.sall.pitv.addons.*;
import com.sall.pitv.plugins.*;
import com.sall.pitv.data.*;
import com.sall.pitv.net.*;

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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/** Netflix-style rows of posters: one row per category, each scrolling sideways. */
public final class Rows extends RecyclerView {
    static final int POSTER = 0, WIDE = 1, CHANNEL = 2;
    /** a collection's folders (Folders.Folder), as wide or tall tiles */
    static final int FOLDER_WIDE = 3, FOLDER_TALL = 4;
    /** the "Voir tout" tile closing a row that has a full page */
    private static final int MORE = 5;

    static final class Row {
        final String title;
        final int style;
        /** Model.Item, Library.Progress for "continue watching", or Folders.Folder */
        public final List<Object> items;
        /** what its full page shows: an add-on's catalogue (Addons.Row) or a category (Model.Category) */
        Object all;

        Row(String title, int style, List<?> items) {
            this.title = title;
            this.style = style;
            this.items = new ArrayList<>(items);
        }

        Row all(Object o) {
            all = o;
            return this;
        }

        /** its cards, with the "Voir tout" tile */
        int count() {
            return items.size() + (all != null && !items.isEmpty() ? 1 : 0);
        }
    }

    interface Callbacks {
        void onFocus(Object item);

        void onClick(Object item);

        /** Pause (or Menu) on a card: favourite it, for instance */
        default void onMenu(Object item) {
        }

        /** the "Voir tout" tile of a row */
        default void onAll(Row row) {
        }
    }

    private final List<Row> rows = new ArrayList<>();
    /** the card each row was left on, by title: up and down come back to it, as on Netflix */
    private final java.util.Map<String, Integer> left = new java.util.HashMap<>();
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
        pool.setMaxRecycledViews(FOLDER_WIDE, 24);
        pool.setMaxRecycledViews(FOLDER_TALL, 24);
        pool.setMaxRecycledViews(MORE, 12);
        setAdapter(new RowAdapter());
    }

    void setRows(List<Row> r) {
        rows.clear();
        for (Row x : r) if (!x.items.isEmpty()) rows.add(x);
        getAdapter().notifyDataSetChanged();
    }

    /** a row added at the end, leaving the highlight where it is */
    void addRow(Row r) {
        if (r.items.isEmpty()) return;
        rows.add(r);
        getAdapter().notifyItemInserted(rows.size() - 1);
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

    /** puts the highlight on row [r]'s card it was left on, bringing the row into view first */
    private void focusRow(int r) {
        ViewHolder h = findViewHolderForAdapterPosition(r);
        if (h instanceof RowHolder) {
            focusIn((RowHolder) h);
            return;
        }
        scrollToPosition(r);
        post(() -> {
            ViewHolder x = findViewHolderForAdapterPosition(r);
            if (x instanceof RowHolder) focusIn((RowHolder) x);
        });
    }

    private void focusIn(RowHolder h) {
        Row r = h.cards.row;
        if (r == null || r.items.isEmpty()) return;
        int at = Math.min(left.getOrDefault(r.title, 0), r.count() - 1);
        View v = h.list.getLayoutManager().findViewByPosition(at);
        if (v != null) {
            v.requestFocus();
            return;
        }
        ((LinearLayoutManager) h.list.getLayoutManager()).scrollToPositionWithOffset(at, 0);
        h.list.post(() -> {
            View w = h.list.getLayoutManager().findViewByPosition(at);
            if (w != null) w.requestFocus();
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
            int at = Math.min(left.getOrDefault(r.title, 0), r.count() - 1);
            ((LinearLayoutManager) h.list.getLayoutManager()).scrollToPositionWithOffset(Math.max(0, at), 0);
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }
    }

    static final class CardHolder extends ViewHolder {
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
            return pos < row.items.size() ? row.style : MORE;
        }

        @NonNull
        @Override
        public CardHolder onCreateViewHolder(@NonNull ViewGroup parent, int style) {
            return style == MORE ? moreCard(parent.getContext()) : card(parent.getContext(), style);
        }

        @Override
        public void onBindViewHolder(@NonNull CardHolder h, int pos) {
            boolean more = pos >= row.items.size();
            Object o = more ? null : row.items.get(pos);
            if (more) {
                // as tall as the row's cards
                ViewGroup.LayoutParams lp = h.itemView.getLayoutParams();
                int[] size = size(getContext(), row.style);
                lp.width = Math.min(size[0], Ui.dp(getContext(), 150));
                lp.height = size[1];
                h.itemView.setLayoutParams(lp);
            } else bind(h, o, row.style);
            Row r = row;
            h.itemView.setOnClickListener(v -> {
                if (more) cb.onAll(r);
                else cb.onClick(o);
            });
            h.itemView.setOnKeyListener((v, key, e) -> {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (!more && (key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || key == KeyEvent.KEYCODE_MEDIA_PAUSE || key == KeyEvent.KEYCODE_MENU)) {
                    cb.onMenu(o);
                    return true;
                }
                if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
                    // row by row, onto the card each row was left on, not the nearest one on screen
                    int target = rows.indexOf(row) + (key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
                    if (target < 0) return false;
                    if (target < rows.size()) focusRow(target);
                    return true;
                }
                return false;
            });
            h.itemView.setOnFocusChangeListener((v, has) -> {
                h.grow.onFocusChange(v, has);
                if (has) {
                    left.put(row.title, pos);
                    if (!more) cb.onFocus(o);
                }
            });
        }

        @Override
        public int getItemCount() {
            return row == null ? 0 : row.count();
        }
    }

    // ---------------------------------------------------------------- cards

    /** a card's width and height */
    private static int[] size(Context c, int style) {
        if (style == POSTER) return new int[] {Ui.dp(c, 104), Ui.dp(c, 156)};
        if (style == FOLDER_TALL) return new int[] {Ui.dp(c, 120), Ui.dp(c, 180)};
        if (style == FOLDER_WIDE) return new int[] {Ui.dp(c, 240), Ui.dp(c, 135)};
        int w = Ui.dp(c, style == CHANNEL ? 150 : 196);
        return new int[] {w, w * 9 / 16};
    }

    /** "Voir tout": a glass tile with an arrow */
    private static CardHolder moreCard(Context c) {
        FrameLayout f = new FrameLayout(c);
        f.setFocusable(true);
        f.setClickable(true);
        f.setBackground(Ui.glass(c, Ui.dp(c, 6), 1));
        RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(Ui.dp(c, 104), Ui.dp(c, 156));
        lp.rightMargin = Ui.dp(c, 10);
        f.setLayoutParams(lp);
        TextView t = Ui.text(c, 13, Color.WHITE, true);
        t.setText("Voir tout");
        t.setGravity(Gravity.CENTER);
        android.graphics.drawable.Drawable arrow = c.getDrawable(R.drawable.ic_chevron_right).mutate();
        arrow.setTint(Color.WHITE);
        arrow.setBounds(0, 0, Ui.dp(c, 28), Ui.dp(c, 28));
        t.setCompoundDrawablesRelative(null, arrow, null, null);
        t.setCompoundDrawablePadding(Ui.dp(c, 6));
        f.addView(t, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        Ui.focusCard(f, 1.1f);
        return new CardHolder(f, new ImageView(c), t, null, null);
    }

    static CardHolder card(Context c, int style) {
        int[] size = size(c, style);
        int w = size[0], h = size[1];
        boolean folder = style == FOLDER_WIDE || style == FOLDER_TALL;
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
        if (style == POSTER || folder) {
            if (folder) name.setGravity(Gravity.CENTER);
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
        Ui.focusCard(f, style == POSTER || style == FOLDER_TALL ? 1.1f : 1.07f);
        return new CardHolder(f, img, name, sub, bar);
    }

    private void bind(CardHolder h, Object o, int style) {
        bind(getContext(), h, o, style, episodeCards);
    }

    static void bind(Context c, CardHolder h, Object o, int style, boolean episodeCards) {
        if (o instanceof Folders.Folder) {
            Folders.Folder d = (Folders.Folder) o;
            // the cover carries the folder's name, often on a transparent logo: the text is only for tiles without one
            h.name.setText(d.cover == null || d.cover.isEmpty() ? d.title : "");
            Images.get(c).load(h.image, d.cover, h.itemView.getLayoutParams().width, h.itemView.getLayoutParams().height, false);
            return;
        }
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
