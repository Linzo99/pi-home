package com.sall.pitv;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * Live TV: categories, their channels with what's on now, and the highlighted channel's
 * guide. OK plays it full screen, where up and down zap through the same list.
 */
final class LivePage {
    final LinearLayout view;
    private final MainActivity a;
    private final RecyclerView catList, chanList;
    private final List<String> catNames = new ArrayList<>();
    private final List<List<Model.Item>> catItems = new ArrayList<>();
    private List<Model.Item> channels = new ArrayList<>();
    private int catPos;
    private final ImageView infoLogo;
    private final TextView infoName, infoNow, infoTime, infoDesc, infoNext, header;
    private final ProgressBar infoBar;
    private Model.Item shown;
    private final Runnable pickCat = this::applyCat;
    private int pendingCat = -1;

    LivePage(MainActivity a) {
        this.a = a;
        view = new LinearLayout(a);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setPadding(a.dp(28), a.dp(24), a.dp(28), 0);

        header = Ui.line(a, 22, Color.WHITE, true);
        header.setText("TV en direct");
        view.addView(header, Ui.margins(-1, -2, a.dp(8), 0, 0, a.dp(12)));

        LinearLayout cols = new LinearLayout(a);
        view.addView(cols, new LinearLayout.LayoutParams(-1, 0, 1f));

        catList = list();
        catList.setAdapter(new CatAdapter());
        cols.addView(catList, new LinearLayout.LayoutParams(a.dp(190), -1));

        chanList = list();
        chanList.setAdapter(new ChanAdapter());
        cols.addView(chanList, Ui.margins(a.dp(330), -1, a.dp(12), 0, 0, 0));

        LinearLayout info = new LinearLayout(a);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(a.dp(22), a.dp(4), a.dp(8), 0);
        infoLogo = new ImageView(a);
        infoLogo.setScaleType(ImageView.ScaleType.FIT_START);
        info.addView(infoLogo, new LinearLayout.LayoutParams(a.dp(120), a.dp(56)));
        infoName = Ui.line(a, 18, Color.WHITE, true);
        info.addView(infoName, Ui.margins(-1, -2, 0, a.dp(10), 0, 0));
        infoNow = Ui.text(a, 15, Color.WHITE, true);
        infoNow.setMaxLines(2);
        info.addView(infoNow, Ui.margins(-1, -2, 0, a.dp(14), 0, 0));
        infoTime = Ui.line(a, 12, Ui.DIM, false);
        info.addView(infoTime, Ui.margins(-1, -2, 0, a.dp(4), 0, 0));
        infoBar = bar();
        info.addView(infoBar, Ui.margins(a.dp(220), a.dp(3), 0, a.dp(6), 0, 0));
        infoDesc = Ui.text(a, 12, Ui.DIM, false);
        infoDesc.setMaxLines(5);
        infoDesc.setLineSpacing(a.dp(2), 1f);
        infoDesc.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(infoDesc, Ui.margins(-1, -2, 0, a.dp(10), 0, 0));
        infoNext = Ui.text(a, 12, Ui.DIM, false);
        infoNext.setLineSpacing(a.dp(5), 1f);
        info.addView(infoNext, Ui.margins(-1, -2, 0, a.dp(16), 0, 0));
        cols.addView(info, new LinearLayout.LayoutParams(0, -1, 1f));

        refresh();
    }

    private RecyclerView list() {
        RecyclerView r = new RecyclerView(a);
        r.setLayoutManager(new Ui.PinnedLayout(a, RecyclerView.VERTICAL, a.dp(120)));
        r.setItemAnimator(null);
        r.setClipToPadding(false);
        r.setPadding(0, a.dp(4), 0, a.dp(200));
        return r;
    }

    private ProgressBar bar() {
        ProgressBar b = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        b.setMax(1000);
        b.setProgressTintList(ColorStateList.valueOf(Ui.RED));
        b.setProgressBackgroundTintList(ColorStateList.valueOf(0x40FFFFFF));
        return b;
    }

    void refresh() {
        catNames.clear();
        catItems.clear();
        List<Model.Item> favs = a.lib.favs(Model.LIVE), recent = a.lib.recentLive();
        if (!favs.isEmpty()) add("★ Favoris", resolve(favs));
        if (!recent.isEmpty()) add("Récentes", resolve(recent));
        if (!a.cat.allLive.isEmpty()) add("Toutes les chaînes", a.cat.allLive);
        for (Model.Category c : a.cat.live) add(c.name, c.items);
        if (catPos >= catNames.size()) catPos = 0;
        catList.getAdapter().notifyDataSetChanged();
        channels = catItems.isEmpty() ? new ArrayList<>() : catItems.get(catPos);
        chanList.getAdapter().notifyDataSetChanged();
        header.setText(a.cat.allLive.isEmpty() ? (a.cat.loaded ? "TV en direct — aucune chaîne" : "TV en direct — chargement…") : "TV en direct");
        if (shown == null && !channels.isEmpty()) showInfo(channels.get(0));
        else if (shown != null) showInfo(shown);
    }

    private void add(String name, List<Model.Item> items) {
        catNames.add(name);
        catItems.add(items);
    }

    /** saved channels, as the current catalogue has them (logos and addresses may change) */
    private List<Model.Item> resolve(List<Model.Item> l) {
        List<Model.Item> out = new ArrayList<>();
        for (Model.Item i : l) {
            Model.Item full = a.cat.byKey.get(i.key());
            out.add(full != null ? full : i);
        }
        return out;
    }

    void focus() {
        focus(0);
    }

    /** the list may not be laid out yet right after the page opens: try again shortly */
    private void focus(int attempt) {
        RecyclerView target = channels.isEmpty() ? catList : chanList;
        View v = target.getLayoutManager().findViewByPosition(target == catList ? catPos : firstVisible(target));
        if (v != null) v.requestFocus();
        else if (attempt < 10) target.postDelayed(() -> focus(attempt + 1), 50);
    }

    private static int firstVisible(RecyclerView r) {
        return Math.max(0, ((Ui.PinnedLayout) r.getLayoutManager()).findFirstVisibleItemPosition());
    }

    /** Back from the channels goes to the categories first */
    boolean back() {
        if (chanList.hasFocus()) {
            View v = catList.getLayoutManager().findViewByPosition(catPos);
            if (v != null) {
                v.requestFocus();
                return true;
            }
        }
        return false;
    }

    private void applyCat() {
        if (pendingCat < 0 || pendingCat >= catItems.size()) return;
        catPos = pendingCat;
        channels = catItems.get(catPos);
        chanList.scrollToPosition(0);
        chanList.getAdapter().notifyDataSetChanged();
        for (int i = 0; i < catList.getChildCount(); i++) {
            RecyclerView.ViewHolder h = catList.getChildViewHolder(catList.getChildAt(i));
            catList.getAdapter().onBindViewHolder(h, h.getBindingAdapterPosition());
        }
    }

    private void showInfo(Model.Item ch) {
        shown = ch;
        Images.get(a).load(infoLogo, ch.image, a.dp(120), a.dp(56), true);
        infoName.setText((ch.num > 0 ? ch.num + "  " : "") + ch.name);
        List<Model.Prog> up = a.cat.epg.upcoming(ch);
        long now = System.currentTimeMillis();
        Model.Prog cur = !up.isEmpty() && up.get(0).start <= now ? up.get(0) : null;
        if (cur != null) {
            infoNow.setText(cur.title);
            infoTime.setText(Ui.clock(cur.start) + " – " + Ui.clock(cur.stop) + " · encore " + Ui.duration(cur.stop - now));
            infoBar.setVisibility(View.VISIBLE);
            infoBar.setProgress((int) (1000 * (now - cur.start) / Math.max(1, cur.stop - cur.start)));
            infoDesc.setText(cur.desc == null ? "" : cur.desc);
        } else {
            infoNow.setText(up.isEmpty() ? "Pas de programme disponible" : "");
            infoTime.setText("");
            infoBar.setVisibility(View.GONE);
            infoDesc.setText("");
        }
        StringBuilder next = new StringBuilder();
        int n = 0;
        for (Model.Prog p : up) {
            if (p == cur) continue;
            if (n++ >= 6) break;
            next.append(Ui.clock(p.start)).append("   ").append(p.title).append('\n');
        }
        infoNext.setText(next.length() == 0 ? "" : "À suivre\n" + next.toString().trim());
        if (up.isEmpty() || cur == null) {
            a.cat.async(() -> a.cat.epg.fetchShort(a.cat.source, ch), changed -> {
                if (Boolean.TRUE.equals(changed) && shown == ch) {
                    showInfo(ch);
                    chanList.getAdapter().notifyDataSetChanged();
                }
            });
        }
    }

    // ---------------------------------------------------------------- lists

    private static final class Holder extends RecyclerView.ViewHolder {
        final TextView name, sub;
        final ImageView logo;
        final ProgressBar bar;

        Holder(View v, TextView name, TextView sub, ImageView logo, ProgressBar bar) {
            super(v);
            this.name = name;
            this.sub = sub;
            this.logo = logo;
            this.bar = bar;
        }
    }

    private void styleRow(View v, boolean focused, boolean selected) {
        v.setBackground(focused ? Ui.rounded(Color.WHITE, a.dp(6)) : selected ? Ui.rounded(0x33FFFFFF, a.dp(6)) : null);
    }

    private final class CatAdapter extends RecyclerView.Adapter<Holder> {
        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup p, int t) {
            TextView v = Ui.line(a, 13, Ui.DIM, true);
            v.setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10));
            v.setFocusable(true);
            v.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            return new Holder(v, v, null, null, null);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int pos) {
            TextView v = h.name;
            v.setText(catNames.get(pos) + "  " + catItems.get(pos).size());
            Runnable style = () -> {
                boolean f = v.hasFocus();
                styleRow(v, f, pos == catPos);
                v.setTextColor(f ? Color.BLACK : pos == catPos ? Color.WHITE : Ui.DIM);
            };
            style.run();
            v.setOnFocusChangeListener((x, has) -> {
                style.run();
                if (has && pos != catPos) {
                    pendingCat = pos;
                    a.ui.removeCallbacks(pickCat);
                    a.ui.postDelayed(pickCat, 220);
                }
            });
            v.setOnClickListener(x -> {
                pendingCat = pos;
                applyCat();
                focus();
            });
            v.setOnKeyListener((x, key, e) -> {
                if (e.getAction() == KeyEvent.ACTION_DOWN && key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (pos != catPos) {
                        pendingCat = pos;
                        applyCat();
                    }
                    focus();
                    return true;
                }
                if (e.getAction() == KeyEvent.ACTION_DOWN && key == KeyEvent.KEYCODE_DPAD_LEFT) {
                    a.focusRail();
                    return true;
                }
                return false;
            });
        }

        @Override
        public int getItemCount() {
            return catNames.size();
        }
    }

    private final class ChanAdapter extends RecyclerView.Adapter<Holder> {
        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup p, int t) {
            LinearLayout row = new LinearLayout(a);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setFocusable(true);
            row.setPadding(a.dp(10), a.dp(6), a.dp(10), a.dp(6));
            row.setLayoutParams(new RecyclerView.LayoutParams(-1, a.dp(50)));
            FrameLayout lf = new FrameLayout(a);
            lf.setBackground(Ui.rounded(0xFF262626, a.dp(4)));
            ImageView logo = new ImageView(a);
            logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int pad = a.dp(3);
            logo.setPadding(pad, pad, pad, pad);
            lf.addView(logo, new FrameLayout.LayoutParams(-1, -1));
            row.addView(lf, new LinearLayout.LayoutParams(a.dp(58), a.dp(36)));
            LinearLayout texts = new LinearLayout(a);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView name = Ui.line(a, 13, Color.WHITE, true);
            texts.addView(name);
            TextView sub = Ui.line(a, 11, Ui.FAINT, false);
            texts.addView(sub, Ui.margins(-1, -2, 0, a.dp(3), 0, 0));
            ProgressBar b = bar();
            texts.addView(b, Ui.margins(-1, a.dp(2), 0, a.dp(4), 0, 0));
            row.addView(texts, Ui.margins(0, -2, a.dp(12), 0, 0, 0));
            ((LinearLayout.LayoutParams) texts.getLayoutParams()).weight = 1;
            return new Holder(row, name, sub, logo, b);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int pos) {
            Model.Item ch = channels.get(pos);
            boolean fav = a.lib.isFav(ch);
            h.name.setText((ch.num > 0 ? ch.num + "  " : "") + ch.name + (fav ? "  ★" : ""));
            Images.get(a).load(h.logo, ch.image, a.dp(52), a.dp(30), true);
            Model.Prog now = a.cat.epg.now(ch);
            long t = System.currentTimeMillis();
            h.sub.setText(now == null ? "" : now.title);
            h.sub.setVisibility(now == null ? View.GONE : View.VISIBLE);
            h.bar.setVisibility(now == null ? View.GONE : View.VISIBLE);
            if (now != null) h.bar.setProgress((int) (1000 * (t - now.start) / Math.max(1, now.stop - now.start)));
            Runnable style = () -> {
                boolean f = h.itemView.hasFocus();
                styleRow(h.itemView, f, false);
                h.name.setTextColor(f ? Color.BLACK : Color.WHITE);
                h.sub.setTextColor(f ? 0xB3000000 : Ui.FAINT);
            };
            style.run();
            h.itemView.setOnFocusChangeListener((v, has) -> {
                style.run();
                if (has) showInfo(ch);
            });
            h.itemView.setOnClickListener(v -> a.playChannel(ch, channels));
            h.itemView.setOnKeyListener((v, key, e) -> {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || key == KeyEvent.KEYCODE_MEDIA_PAUSE || key == KeyEvent.KEYCODE_MENU) {
                    boolean now2 = a.lib.toggleFav(ch);
                    Toast.makeText(a, now2 ? ch.name + " ajoutée aux favoris" : ch.name + " retirée des favoris", Toast.LENGTH_SHORT).show();
                    notifyItemChanged(pos);
                    return true;
                }
                if (key == KeyEvent.KEYCODE_DPAD_LEFT) {
                    View c = catList.getLayoutManager().findViewByPosition(catPos);
                    if (c != null) c.requestFocus();
                    return true;
                }
                if (key == KeyEvent.KEYCODE_DPAD_RIGHT) return true;
                return false;
            });
        }

        @Override
        public int getItemCount() {
            return channels.size();
        }
    }
}
