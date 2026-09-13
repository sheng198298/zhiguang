import { useEffect, useRef, useState } from "react";
import { useParams } from "react-router-dom";
import AppLayout from "@/components/layout/AppLayout";
import MainHeader from "@/components/layout/MainHeader";
import Tag from "@/components/common/Tag";
import SectionHeader from "@/components/common/SectionHeader";
import { ArrowRightIcon } from "@/components/icons/Icon";
import AuthStatus from "@/features/auth/AuthStatus";
import styles from "./CourseDetailPage.module.css";
import { knowpostService } from "@/services/knowpostService";
import { useAuth } from "@/context/AuthContext";
import type { KnowpostDetailResponse } from "@/types/knowpost";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import LikeFavBar from "@/components/common/LikeFavBar";
import FollowButton from "@/components/common/FollowButton";

const CourseDetailPage = () => {
  const { id } = useParams<{ id: string }>();
  const { tokens, user } = useAuth();
  const [detail, setDetail] = useState<KnowpostDetailResponse | null>(null);
  const [, setActiveImage] = useState(0);
  const [contentText, setContentText] = useState<string>("");
  const [error, setError] = useState<string | null>(null);
  const [previewOpen, setPreviewOpen] = useState(false);
  const [previewIndex, setPreviewIndex] = useState(0);
  const [contentError, setContentError] = useState<string | null>(null);
  const previewBoxRef = useRef<HTMLDivElement | null>(null);
  const [showNavLeft, setShowNavLeft] = useState(false);
  const [showNavRight, setShowNavRight] = useState(false);
  const [isTouch, setIsTouch] = useState(false);
  // RAG 问答状态
  const [ragQuestion, setRagQuestion] = useState<string>("");
  const [ragAnswer, setRagAnswer] = useState<string>("");
  const [ragLoading, setRagLoading] = useState<boolean>(false);
  const [ragError, setRagError] = useState<string | null>(null);
  const ragESRef = useRef<EventSource | null>(null);
  const [ragTopK] = useState<number>(5);
  const [ragMaxTokens] = useState<number>(1024);
  // 从头像 URL 推断作者 ID（示例：.../avatars/3-xxxx.jpg → 3）
  const parseAvatarUserId = (url?: string): number | undefined => {
    if (!url) return undefined;
    const m = url.match(/\/avatars\/(\d+)-/);
    return m ? Number(m[1]) : undefined;
  };

  useEffect(() => {
    let cancelled = false;
    const run = async () => {
      if (!id) return;
      setError(null);
      try {
        const resp = await knowpostService.detail(id, tokens?.accessToken ?? undefined);
        if (cancelled) return;
        setDetail(resp);
        setActiveImage(0);
        // 异步加载正文内容
        if (resp.contentUrl) {
          const allowAnonymous = resp.visible === "public";
          if (allowAnonymous || !!tokens?.accessToken) {
            try {
              const text = await fetch(resp.contentUrl, { credentials: "omit" }).then(r => {
                if (!r.ok) throw new Error(`HTTP ${r.status}`);
                return r.text();
              });
              if (!cancelled) {
                setContentText(text);
                setContentError(null);
              }
            } catch (e) {
              if (!cancelled) setContentError("正文暂不可读，可能为非公开或跨域受限");
            }
          } else {
            setContentError("该知文非公开，请登录后查看正文");
          }
        }
      } catch (err) {
        const msg = err instanceof Error ? err.message : "加载失败";
        if (!cancelled) setError(msg);
      }
    };
    run();
    return () => { cancelled = true; };
  }, [id, tokens?.accessToken]);

  // 正文本体图片点击 → 在 detail.images 中定位后打开预览
  const openImagePreview = (src: string) => {
    const idx = detail?.images?.findIndex(u => u === src) ?? -1;
    openPreview(idx >= 0 ? idx : 0);
  };

  useEffect(() => {
    const touch = "ontouchstart" in window || navigator.maxTouchPoints > 0;
    setIsTouch(touch);
    if (touch) {
      setShowNavLeft(true);
      setShowNavRight(true);
    }
  }, []);

  const handlePreviewMouseMove = (e: React.MouseEvent<HTMLDivElement>) => {
    if (isTouch) return;
    const el = previewBoxRef.current;
    if (!el) return;
    const rect = el.getBoundingClientRect();
    const x = e.clientX - rect.left;
    const threshold = Math.max(60, Math.min(120, rect.width * 0.08));
    setShowNavLeft(x < threshold);
    setShowNavRight(x > rect.width - threshold);
  };

  const handlePreviewMouseLeave = () => {
    if (isTouch) return;
    setShowNavLeft(false);
    setShowNavRight(false);
  };

  const openPreview = (index: number) => {
    setPreviewIndex(index);
    setPreviewOpen(true);
  };

  const prevImage = () => {
    if (!detail?.images?.length) return;
    setPreviewIndex((i) => (i - 1 + detail.images.length) % detail.images.length);
  };

  const nextImage = () => {
    if (!detail?.images?.length) return;
    setPreviewIndex((i) => (i + 1) % detail.images.length);
  };

  // 启动 RAG 流式问答
  const startRag = () => {
    if (!id) return;
    const q = ragQuestion.trim();
    if (!q) return;
    if (detail && detail.visible !== "public") {
      setRagError("仅公开知文支持问答");
      return;
    }
    setRagError(null);
    setRagAnswer("");
    // 关闭之前的连接
    if (ragESRef.current) {
      try { ragESRef.current.close(); } catch {}
      ragESRef.current = null;
    }
    const url = `/api/v1/knowposts/${id}/qa/stream?question=${encodeURIComponent(q)}&topK=${ragTopK}&maxTokens=${ragMaxTokens}`;
    const es = new EventSource(url);
    ragESRef.current = es;
    setRagLoading(true);
    es.onmessage = (e) => {
      setRagAnswer((prev) => prev + (e.data ?? ""));
    };
    es.onerror = () => {
      setRagLoading(false);
      // 不展示“连接中断或后端异常”，静默关闭连接
      try { es.close(); } catch {}
      ragESRef.current = null;
    };
  };

  const stopRag = () => {
    if (ragESRef.current) {
      try { ragESRef.current.close(); } catch {}
      ragESRef.current = null;
    }
    setRagLoading(false);
  };

  useEffect(() => {
    return () => {
      // 页面卸载时关闭 SSE
      if (ragESRef.current) {
        try { ragESRef.current.close(); } catch {}
        ragESRef.current = null;
      }
    };
  }, []);

  return (
    <AppLayout
      header={
        <MainHeader
          headline={detail?.title ?? ""}
          subtitle=""
          rightSlot={<AuthStatus />}
        />
      }
      variant="cardless"
    >
      <article className={styles.detailCard}>
        {error ? <div style={{ color: "var(--color-danger)" }}>{error}</div> : null}
        <div className={styles.titleBlock}>
          <div className={styles.titleRow}></div>
          <div className={styles.meta}>
            {detail?.authorAvatar ? (
              <img className={styles.authorAvatar} src={detail.authorAvatar} alt={detail.authorNickname} />
            ) : null}
            <span className={styles.authorName}>{detail?.authorNickname ?? ""}</span>
            {(() => {
              const derivedId = detail?.authorId ?? parseAvatarUserId(detail?.authorAvatar);
              const isSelf = (derivedId && user?.id === derivedId) || (!!detail?.authorNickname && !!user?.nickname && detail.authorNickname === user.nickname);
              return derivedId && !isSelf ? <FollowButton targetUserId={derivedId} /> : null;
            })()}
          </div>
          <div className={styles.tagList}>
            {(detail?.tags ?? []).map(tag => (
              <Tag key={tag}>#{tag}</Tag>
            ))}
          </div>
          <div className={styles.meta}>
            {detail?.publishTime ? (
              <span>{new Date(detail.publishTime).toLocaleDateString("zh-CN")}</span>
            ) : null}
          </div>
          <div className={styles.bottomBar}>
            {detail ? (
              <LikeFavBar
                entityId={detail.id}
                initialCounts={{ like: detail.likeCount ?? 0, fav: detail.favoriteCount ?? 0 }}
                initialState={{ liked: detail.liked, faved: detail.faved }}
              />
            ) : null}
          </div>
        </div>

        <SectionHeader title="内容正文" subtitle="" />

        <div className={styles.contentRow}>
          <div className={styles.contentMain}>
            <div className={`${styles.body} ${styles.markdown}`}>
              {contentText ? (
                <ReactMarkdown
                  remarkPlugins={[remarkGfm]}
                  components={{
                    a: ({ node, ...props }) => (
                      <a {...props} target="_blank" rel="noreferrer" />
                    ),
                    img: ({ node, ...props }) => (
                      <img
                        {...props}
                        style={{ maxWidth: "100%", borderRadius: 12, cursor: "zoom-in" }}
                        onClick={(e) => {
                          e.stopPropagation();
                          openImagePreview(props.src ?? "");
                        }}
                      />
                    ),
                  }}
                >
                  {contentText}
                </ReactMarkdown>
              ) : (
                "暂无内容"
              )}
            </div>
            {contentError ? (
              <div style={{ color: "var(--color-danger)" }}>{contentError} {detail?.contentUrl ? (<a href={detail.contentUrl} target="_blank" rel="noreferrer">查看原文</a>) : null}</div>
            ) : null}
          </div>

          <aside className={styles.ragPanel}>
            <div className={styles.ragBody}>
              <textarea
                className={styles.ragTextarea}
                placeholder="围绕本知文提问，例如：这篇知文的核心观点是什么？"
                value={ragQuestion}
                onChange={(e) => setRagQuestion(e.target.value)}
              />
              <div className={styles.ragControls}>
                <button
                  type="button"
                  className={`${styles.ragBtn} ${styles.ragBtnPrimary}`}
                  onClick={startRag}
                  disabled={ragLoading || !ragQuestion.trim()}
                >
                  {ragLoading ? "生成中…" : "发送"}
                </button>
                <button type="button" className={`${styles.ragBtn} ${styles.ragBtnGhost}`} onClick={stopRag} disabled={!ragLoading}>
                  停止
                </button>
              </div>
              <div className={styles.ragHint}>
                说明：仅“公开”知文支持问答，答案基于当前知文的索引片段实时生成。
              </div>
              {ragError ? (
                <div style={{ color: "var(--color-danger)" }}>{ragError}</div>
              ) : null}
              <div className={styles.ragAnswer}>
                {ragAnswer ? (
                  <div className={styles.markdown}>
                    <ReactMarkdown
                      remarkPlugins={[remarkGfm]}
                      components={{
                        a: ({ node, ...props }) => (
                          <a {...props} target="_blank" rel="noreferrer" />
                        ),
                        img: ({ node, ...props }) => (
                          <img {...props} style={{ maxWidth: "100%", borderRadius: 12 }} />
                        ),
                      }}
                    >
                      {ragAnswer}
                    </ReactMarkdown>
                  </div>
                ) : (
                  <div className={styles.ragPlaceholder}>
                    {ragLoading ? "等待生成…" : "这里将展示答案（支持流式）"}
                  </div>
                )}
              </div>
            </div>
          </aside>
        </div>

        {previewOpen && detail?.images?.length ? (
          <div className={styles.previewOverlay} onClick={() => setPreviewOpen(false)}>
            <div
              className={styles.previewBox}
              ref={previewBoxRef}
              onMouseMove={handlePreviewMouseMove}
              onMouseLeave={handlePreviewMouseLeave}
              onClick={(e) => e.stopPropagation()}
            >
              <img className={styles.previewImage} src={detail.images[previewIndex]} alt={detail.title} />
              <button
                type="button"
                className={`${styles.navButton} ${styles.navButtonLeft} ${showNavLeft ? styles.navButtonVisible : ""}`}
                onClick={(e) => { e.stopPropagation(); prevImage(); }}
                aria-label="上一张"
              >
                <ArrowRightIcon width={24} height={24} style={{ transform: "rotate(180deg)" }} />
              </button>
              <button
                type="button"
                className={`${styles.navButton} ${styles.navButtonRight} ${showNavRight ? styles.navButtonVisible : ""}`}
                onClick={(e) => { e.stopPropagation(); nextImage(); }}
                aria-label="下一张"
              >
                <ArrowRightIcon width={24} height={24} />
              </button>
              <button type="button" className={styles.closeButton} onClick={(e) => { e.stopPropagation(); setPreviewOpen(false); }} aria-label="关闭">✕</button>
            </div>
          </div>
        ) : null}
      </article>
    </AppLayout>
  );
};

export default CourseDetailPage;
