import { useEffect, useRef, useState, useCallback } from "react";
import AppLayout from "@/components/layout/AppLayout";
import MainHeader from "@/components/layout/MainHeader";
import CourseCard from "@/components/cards/CourseCard";
import LikeFavBar from "@/components/common/LikeFavBar";
import { knowpostService } from "@/services/knowpostService";
import { useAuth } from "@/context/AuthContext";
import AuthStatus from "@/features/auth/AuthStatus";
import type { FeedResponse } from "@/types/knowpost";
import styles from "./HomePage.module.css";

type FeedItem = {
  id: string;
  title: string;
  description: string;
  coverImage?: string;
  tags: string[];
  tagJson?: string;
  authorAvatar?: string;
  authorAvator?: string;
  authorNickname: string;
  likeCount?: number;
  favoriteCount?: number;
  liked?: boolean;
  faved?: boolean;
};

type Source = "recommend" | "following" | "public";

const TABS: { key: Source; label: string }[] = [
  { key: "recommend", label: "推荐" },
  { key: "following", label: "关注" },
  { key: "public", label: "公共" }
];

const HomePage = () => {
  const { user } = useAuth();
  const [items, setItems] = useState<FeedItem[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);
  const [source, setSource] = useState<Source>("public");
  const [cursor, setCursor] = useState<string | undefined>(undefined);
  const [page, setPage] = useState<number>(1);
  const [hasMore, setHasMore] = useState<boolean>(true);
  const loadingMore = useRef(false);

  const fetchPage = useCallback(
    async (src: Source, opts: { cursor?: string; page?: number; append?: boolean }) => {
      if (loadingMore.current) return;
      loadingMore.current = true;
      setError(null);
      try {
        let resp: FeedResponse;
        if (src === "recommend") {
          resp = await knowpostService.recommend(opts.cursor, 12);
        } else if (src === "following") {
          resp = await knowpostService.following(opts.cursor, 12);
        } else {
          resp = await knowpostService.feed(opts.page ?? 1, 20);
        }

        const newItems = resp.items ?? [];
        setItems(prev => (opts.append ? [...prev, ...newItems] : newItems));
        setCursor(resp.cursor ?? undefined);
        setHasMore(resp.hasMore);
        setSource(src);
        if (src === "public") setPage(opts.page ?? 1);
      } catch (err) {
        const msg = err instanceof Error ? err.message : "加载失败";
        setError(msg);
      } finally {
        loadingMore.current = false;
      }
    },
    []
  );

  useEffect(() => {
    const src: Source = user ? "recommend" : "public";
    setLoading(true);
    fetchPage(src, {}).finally(() => setLoading(false));
  }, [user, fetchPage]);

  const handleTab = (src: Source) => {
    if (src === source) return;
    if (src !== "public" && !user) {
      setError("请先登录");
      return;
    }
    setLoading(true);
    setItems([]);
    fetchPage(src, {}).finally(() => setLoading(false));
  };

  const handleLoadMore = useCallback(() => {
    if (loadingMore.current || !hasMore || loading) return;
    if (source === "public") {
      fetchPage("public", { page: page + 1, append: true });
    } else {
      fetchPage(source, { cursor, append: true });
    }
  }, [source, cursor, page, hasMore, loading, fetchPage]);

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
          rightSlot={<AuthStatus />}
        />
      }
    >
      <div className={styles.tabs}>
        {TABS.map(tab => (
          <button
            key={tab.key}
            type="button"
            className={source === tab.key ? `${styles.tab} ${styles.tabActive}` : styles.tab}
            onClick={() => handleTab(tab.key)}
          >
            {tab.label}
          </button>
        ))}
      </div>

      {error ? <div className={styles.error}>{error}</div> : null}

      <div className={styles.feedList}>
        {items.map(item => (
          <CourseCard
            key={item.id}
            id={item.id}
            title={item.title}
            summary={item.description ?? ""}
            tags={item.tags ?? []}
            authorTags={(() => {
              try {
                return item.tagJson ? (JSON.parse(item.tagJson) as unknown[]).filter((t) => typeof t === "string") as string[] : [];
              } catch {
                return [];
              }
            })()}
            teacher={{ name: item.authorNickname, avatarUrl: item.authorAvatar ?? item.authorAvator }}
            coverImage={item.coverImage}
            layout="horizontal"
            to={`/post/${item.id}`}
            footerExtra={<LikeFavBar entityId={item.id} compact initialCounts={{ like: item.likeCount ?? 0, fav: item.favoriteCount ?? 0 }} initialState={{ liked: item.liked, faved: item.faved }} />}
          />
        ))}
        {loading ? <div className={styles.feedListHint}>加载中…</div> : null}
        {!loading && items.length === 0 ? (
          <div className={styles.feedListHint}>暂无内容</div>
        ) : null}
      </div>
    </AppLayout>
  );
};

export default HomePage;
