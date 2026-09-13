import { useEffect, useState, useCallback } from "react";
import AppLayout from "@/components/layout/AppLayout";
import MainHeader from "@/components/layout/MainHeader";
import CourseCard from "@/components/cards/CourseCard";
import LikeFavBar from "@/components/common/LikeFavBar";
import { knowpostService } from "@/services/knowpostService";
import AuthStatus from "@/features/auth/AuthStatus";
import type { HotItem } from "@/types/knowpost";
import styles from "./HotListPage.module.css";

type HotWindow = "daily" | "weekly" | "alltime";

const WINDOWS: { id: HotWindow; label: string }[] = [
  { id: "daily", label: "日榜" },
  { id: "weekly", label: "周榜" },
  { id: "alltime", label: "总榜" }
];

const formatHeat = (score: number) => {
  if (score >= 10000) return `${(score / 10000).toFixed(1)}万`;
  return Math.round(score).toString();
};

const HotListPage = () => {
  const [hotWindow, setHotWindow] = useState<HotWindow>("daily");
  const [items, setItems] = useState<HotItem[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);
  const [page, setPage] = useState<number>(1);
  const [hasMore, setHasMore] = useState<boolean>(false);

  const load = useCallback(async (w: HotWindow, p: number, append = false) => {
    setLoading(true);
    setError(null);
    try {
      const resp = await knowpostService.hotlist(w, p, 20);
      setItems(prev => (append ? [...prev, ...resp.items] : resp.items));
      setHasMore(resp.hasMore);
      setPage(p);
    } catch (err) {
      setError(err instanceof Error ? err.message : "加载失败");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load("daily", 1);
  }, [load]);

  const handleWindow = (id: string) => {
    const w = id as HotWindow;
    if (w === hotWindow) return;
    setHotWindow(w);
    setItems([]);
    load(w, 1);
  };

  const handleLoadMore = useCallback(() => {
    if (loading || !hasMore) return;
    load(hotWindow, page + 1, true);
  }, [loading, hasMore, hotWindow, page, load]);

  // 滚动监听：触底加载更多
  useEffect(() => {
    const onScroll = () => {
      if (window.innerHeight + window.scrollY >= document.body.offsetHeight - 300) {
        handleLoadMore();
      }
    };
    window.addEventListener("scroll", onScroll, { passive: true });
    return () => window.removeEventListener("scroll", onScroll);
  }, [handleLoadMore]);

  return (
    <AppLayout
      header={
        <MainHeader
          headline="热点榜单"
          subtitle="全站热门内容排行"
          tabs={WINDOWS.map(w => ({
            id: w.id,
            label: w.label,
            active: hotWindow === w.id,
            onSelect: handleWindow
          }))}
          rightSlot={<AuthStatus />}
        />
      }
    >
      {error ? <div className={styles.error}>{error}</div> : null}

      <div className={styles.list}>
        {items.map((item, idx) => (
          <div key={item.id} className={styles.rankItem}>
            <div className={`${styles.rankNum} ${idx < 3 ? styles.rankTop : ""}`}>{idx + 1}</div>
            <div className={styles.cardWrap}>
              <CourseCard
                id={item.id}
                title={item.title}
                summary={item.description ?? ""}
                tags={[]}
                teacher={{ name: item.authorNickname, avatarUrl: item.authorAvatar }}
                footerExtra={
                  <div className={styles.heatRow}>
                    <span className={styles.heatScore}>🔥 {formatHeat(item.heatScore)}</span>
                    <span className={styles.viewCount}>👁 {item.viewCount}</span>
                    <LikeFavBar entityId={item.id} compact initialCounts={{ like: item.likeCount, fav: item.favCount }} />
                  </div>
                }
                to={`/post/${item.id}`}
              />
            </div>
          </div>
        ))}
      </div>

      {loading ? <div className={styles.loading}>加载中…</div> : null}
      {!loading && items.length === 0 ? <div className={styles.loading}>暂无内容</div> : null}
    </AppLayout>
  );
};

export default HotListPage;
