package com.sall.pitv.ui;

import com.sall.pitv.R;
import com.sall.pitv.player.*;
import com.sall.pitv.iptv.*;
import com.sall.pitv.addons.*;
import com.sall.pitv.plugins.*;
import com.sall.pitv.data.*;
import com.sall.pitv.net.*;

import android.content.Context;
import android.view.KeyEvent;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * a catalogue's full page: its posters in a grid, the next ones asked for as the highlight nears
 * the end, as in Stremio's "see all"
 */
public final class Grid extends RecyclerView {
    /** the titles after the first [skip]; empty at the end of the catalogue */
    interface Pager {
        List<Model.Item> next(int skip) throws Exception;
    }

    private final MainActivity a;
    private final Rows.Callbacks cb;
    private final Pager pager;
    private final List<Model.Item> items = new ArrayList<>();
    private final Set<String> seen = new HashSet<>();
    private final GridLayoutManager layout;
    private final int cardW, gap;
    private boolean loading, ended;
    /** told how many titles the page holds, and whether more are coming */
    Runnable changed;

    Grid(MainActivity a, List<Model.Item> first, Pager pager, Rows.Callbacks cb) {
        super(a);
        this.a = a;
        this.cb = cb;
        this.pager = pager;
        ended = pager == null;
        add(first);
        cardW = a.dp(104);
        gap = a.dp(14);
        layout = new GridLayoutManager(a, 6);
        setLayoutManager(layout);
        setClipToPadding(false);
        setClipChildren(false);
        setPadding(a.dp(48), a.dp(10), a.dp(48), a.dp(120));
        setItemAnimator(null);
        setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);
        setAdapter(new Cards());
        // too short to fill the screen: ask for the next ones at once
        if (items.size() < 40) more();
    }

    int count() {
        return items.size();
    }

    boolean loading() {
        return !ended;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        int span = Math.max(1, (w - getPaddingLeft() - getPaddingRight() + gap) / (cardW + gap));
        if (span != layout.getSpanCount()) post(() -> layout.setSpanCount(span));
    }

    void focusFirst() {
        scrollToPosition(0);
        post(() -> {
            android.view.View v = layout.findViewByPosition(0);
            if (v != null) v.requestFocus();
        });
    }

    /** new titles only: some add-ons answer the last page again past their end */
    private int add(List<Model.Item> l) {
        int n = 0;
        for (Model.Item i : l) if (seen.add(i.key())) {
            items.add(i);
            n++;
        }
        return n;
    }

    private void more() {
        if (loading || ended) return;
        loading = true;
        int skip = items.size();
        a.cat.async(() -> pager.next(skip), l -> {
            loading = false;
            // a failed page (null) is asked again on the next move
            if (l == null) return;
            int from = items.size();
            int n = add(l);
            if (n == 0) ended = true;
            else getAdapter().notifyItemRangeInserted(from, n);
            if (changed != null) changed.run();
        });
    }

    private final class Cards extends Adapter<Rows.CardHolder> {
        @NonNull
        @Override
        public Rows.CardHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            Rows.CardHolder h = Rows.card(parent.getContext(), Rows.POSTER);
            RecyclerView.LayoutParams lp = (RecyclerView.LayoutParams) h.itemView.getLayoutParams();
            lp.rightMargin = gap;
            lp.bottomMargin = gap;
            return h;
        }

        @Override
        public void onBindViewHolder(@NonNull Rows.CardHolder h, int pos) {
            Model.Item o = items.get(pos);
            Rows.bind(a, h, o, Rows.POSTER, false);
            // a card reused while it had the highlight keeps its frame and size otherwise
            if (!h.itemView.isFocused()) h.grow.onFocusChange(h.itemView, false);
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
                if (!has) return;
                cb.onFocus(o);
                // three rows from the end: the next page, so it's there before the highlight
                if (pos >= items.size() - layout.getSpanCount() * 3) more();
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }
}
