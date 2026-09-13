import AppLayout from "@/components/layout/AppLayout";
import MainHeader from "@/components/layout/MainHeader";
import SectionHeader from "@/components/common/SectionHeader";
import TagInput from "@/components/common/TagInput";
import Select from "@/components/common/Select";
import { useRef, useState } from "react";
import { knowpostService, uploadToPresigned, computeSha256 } from "@/services/knowpostService";
import AuthStatus from "@/features/auth/AuthStatus";
import { useAuth } from "@/context/AuthContext";
import styles from "./CreatePage.module.css";

const CreatePage = () => {
  const { tokens } = useAuth();
  const [type, setType] = useState("图文");
  const [tags, setTags] = useState<string[]>([]);
  const [title, setTitle] = useState("");
  const [content, setContent] = useState("");
  const [isFree, setIsFree] = useState(true);
  const [price, setPrice] = useState<number>(0);
  const [visiblePublic, setVisiblePublic] = useState(true);
  const [summary, setSummary] = useState("");
  const [aiSummaryEnabled, setAiSummaryEnabled] = useState(false);
  const [aiSummaryLoading, setAiSummaryLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [postId, setPostId] = useState<string | null>(null);
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);

  // 图片直传相关
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const contentRef = useRef<HTMLTextAreaElement | null>(null);
  const [imageUploading, setImageUploading] = useState(false);
  const [uploadedImgUrls, setUploadedImgUrls] = useState<string[]>([]);
  const MAX_IMAGES = 15;

  const ensureDraft = async (): Promise<string> => {
    if (postId) return postId;
    const resp = await knowpostService.createDraft();
    const idStr = String(resp.id);
    setPostId(idStr);
    setMessage(`草稿已创建：${idStr}`);
    return idStr;
  };

  const handleSelectImages = async (files: FileList | null) => {
    if (!files || files.length === 0) return;
    setError(null);
    setMessage(null);
    setImageUploading(true);
    try {
      const id = await ensureDraft();
      const currentCount = uploadedImgUrls.length;
      const remaining = Math.max(0, MAX_IMAGES - currentCount);
      if (remaining <= 0) {
        setError(`最多可选择 ${MAX_IMAGES} 张图片`);
        return;
      }
      const allSelected = Array.from(files);
      const arr = allSelected.slice(0, remaining);
      for (const f of arr) {
        const match = f.name.match(/\.[^.]+$/);
        const ext = match ? match[0] : ".jpg";
        const contentType = f.type || (ext.toLowerCase() === ".png" ? "image/png" : ext.toLowerCase() === ".svg" ? "image/svg+xml" : "image/jpeg");
        const presign = await knowpostService.presign({
          scene: "knowpost_image",
          postId: id,
          contentType,
          ext
        });
        await uploadToPresigned(presign.putUrl, presign.headers, f);
        const publicUrl = presign.putUrl.split("?")[0];
        setUploadedImgUrls(prev => [...prev, publicUrl]);
        // 在正文光标处插入内嵌图片 markdown，未聚焦则追加到末尾
        const md = `\n\n![${f.name.replace(/\.[^.]+$/, "")}](${publicUrl})\n`;
        setContent(prev => {
          const el = contentRef.current;
          if (!el) return prev + md;
          const start = el.selectionStart ?? prev.length;
          const end = el.selectionEnd ?? prev.length;
          const next = prev.slice(0, start) + md + prev.slice(end);
          requestAnimationFrame(() => {
            el.focus();
            const pos = start + md.length;
            el.setSelectionRange(pos, pos);
          });
          return next;
        });
      }
      const ignored = allSelected.length - arr.length;
      setMessage(`图片上传成功：${arr.length} 张${ignored > 0 ? `（已超过上限，忽略 ${ignored} 张）` : ""}`);
    } catch (err) {
      const msg = err instanceof Error ? err.message : "图片上传失败";
      setError(msg);
    } finally {
      setImageUploading(false);
    }
  };

  const handlePublish = async () => {
    setMessage(null);
    setError(null);
    if (!title.trim()) {
      setError("请填写标题");
      return;
    }
    if (!content.trim()) {
      setError("请填写内容正文");
      return;
    }
    if (summary.trim().length > 50) {
      setError("摘要不能超过50字");
      return;
    }
    setSubmitting(true);
    try {
      // 1) 创建或复用草稿
      const id = await ensureDraft();

      // 2) 预签名上传正文（将文本作为 Markdown 文件上传）
      const file = new File([content], "content.md", { type: "text/markdown" });
      const size = file.size;
      const sha256 = await computeSha256(file);
      const presign = await knowpostService.presign({
        scene: "knowpost_content",
        postId: id,
        contentType: "text/markdown",
        ext: ".md"
      });
      const { etag } = await uploadToPresigned(presign.putUrl, presign.headers, file);

      // 3) 确认正文
      await knowpostService.confirmContent(id, {
        objectKey: presign.objectKey,
        etag,
        size,
        sha256
      });

      // 4) 生成/使用摘要并保存元数据（标题、标签、摘要等）
      const description = summary.trim();
      await knowpostService.update(id, {
        title: title.trim(),
        tags: tags.length ? tags : undefined,
        imgUrls: uploadedImgUrls.length ? uploadedImgUrls : undefined,
        visible: visiblePublic ? "public" : "private",
        isTop: false,
        description: description || undefined
      });

      // 5) 发布
      await knowpostService.publish(id);
      setMessage("发布成功 ✅");
    } catch (err) {
      const msg = err instanceof Error ? err.message : "发布失败";
      setError(msg);
    } finally {
      setSubmitting(false);
    }
  };
  const handleToggleAiSummary = async () => {
    // 切换开关；开启时触发生成
    if (!aiSummaryEnabled) {
      if (!tokens?.accessToken) {
        setError("请先登录以使用 AI 摘要");
        return;
      }
      if (!content.trim()) {
        setError("正文为空，无法生成摘要");
        return;
      }
      setAiSummaryLoading(true);
      setMessage(null);
      setError(null);
      try {
        const resp = await knowpostService.suggestDescription(content, tokens.accessToken);
        const desc = (resp.description ?? "").slice(0, 50);
        setSummary(desc);
        setAiSummaryEnabled(true);
        setMessage("AI 摘要已生成");
      } catch (err) {
        const msg = err instanceof Error ? err.message : "生成失败";
        setError(msg);
      } finally {
        setAiSummaryLoading(false);
      }
    } else {
      setAiSummaryEnabled(false);
    }
  };

  return (
    <AppLayout
      header={
        <MainHeader
          headline="创建新内容"
          subtitle="分享你的知识，让更多人受益"
          rightSlot={<AuthStatus />}
        />
      }
    >
      <div className={styles.formCard}>
        <SectionHeader title="基本信息" subtitle="精准描述你的内容，帮助同学快速了解" />
        <div className={styles.formGrid}>
          <div className={styles.field}>
            <label className={styles.label} htmlFor="title">
              标题 *
            </label>
            <input
              id="title"
              className={styles.input}
              placeholder="输入内容标题"
              value={title}
              onChange={e => setTitle(e.target.value)}
            />
          </div>
          <Select
            id="type"
            label="内容类型 *"
            value={type}
            onChange={setType}
            options={[
              { label: "图文", value: "图文" },
              { label: "视频", value: "视频" }
            ]}
          />
          <div className={`${styles.field} ${styles.fullWidth}`}>
            <label className={styles.label}>图片（多选） *</label>
            <div
              className={styles.uploadBox}
              role="button"
              tabIndex={0}
              onClick={() => {
                if (uploadedImgUrls.length >= MAX_IMAGES) {
                  setError(`最多可选择 ${MAX_IMAGES} 张图片`);
                  return;
                }
                fileInputRef.current?.click();
              }}
              onKeyDown={(e) => { if (e.key === "Enter" || e.key === " ") fileInputRef.current?.click(); }}
            >
              <span>{imageUploading ? "正在上传…" : "点击上传图片"}</span>
              <small>支持 JPG / PNG / SVG，最多 {MAX_IMAGES} 张；单张不超过 5MB（已选 {uploadedImgUrls.length} / {MAX_IMAGES}）</small>
              <input
                ref={fileInputRef}
                type="file"
                accept="image/*"
                multiple
                className={styles.fileInputHidden}
                onChange={e => handleSelectImages(e.target.files)}
              />
            </div>
            {uploadedImgUrls.length > 0 ? (
              <div className={styles.thumbGrid}>
                {uploadedImgUrls.map((url, idx) => (
                  <img key={idx} src={url} alt="" className={styles.thumb} onClick={() => setPreviewUrl(url)} />
                ))}
              </div>
            ) : null}
          </div>
          <div className={`${styles.field} ${styles.fullWidth}`}>
            <label className={styles.label} htmlFor="content">
              内容正文 *
            </label>
            <textarea
              id="content"
              ref={contentRef}
              className={styles.textarea}
              placeholder="写下你的知识内容..."
              value={content}
              onChange={e => setContent(e.target.value)}
            />
          </div>
          <div className={`${styles.field} ${styles.fullWidth}`}>
            <div className={styles.fieldHeader}>
              <label className={styles.label} htmlFor="summary">知识摘要</label>
              <div className={styles.headActions}>
                <span>AI 摘要</span>
                <div
                  className={`${styles.inlineSwitch} ${aiSummaryEnabled ? styles.inlineSwitchOn : ""}`}
                  role="button"
                  tabIndex={0}
                  aria-pressed={aiSummaryEnabled}
                  aria-label="AI 摘要开关"
                  onClick={handleToggleAiSummary}
                  onKeyDown={(e) => { if (e.key === "Enter" || e.key === " ") handleToggleAiSummary(); }}
                />
                {aiSummaryLoading ? <small className={styles.muted}>生成中…</small> : null}
              </div>
            </div>
            <textarea
              id="summary"
              className={styles.textarea}
              placeholder="填写内容摘要（50字以内）"
              value={summary}
              onChange={e => setSummary(e.target.value)}
            />
            <small className={summary.trim().length > 50 ? styles.charCountOver : styles.charCount}>
              {summary.trim().length} / 50
            </small>
          </div>
          <div className={styles.field}>
            <label className={styles.label} htmlFor="price">
              价格
            </label>
            <input
              id="price"
              className={styles.input}
              type="number"
              min="0"
              step="0.1"
              value={isFree ? 0 : price}
              onChange={e => setPrice(Number(e.target.value))}
              disabled={isFree}
            />
          </div>
          <div className={styles.field}>
            <label className={styles.label} htmlFor="tags">
              标签
            </label>
            <TagInput
              id="tags"
              value={tags}
              onChange={setTags}
              placeholder="输入标签后按回车"
            />
          </div>
          <div className={`${styles.field} ${styles.fullWidth}`}>
            <div
              className={styles.toggle}
              role="button"
              tabIndex={0}
              onClick={() => setIsFree(prev => !prev)}
              onKeyDown={(e) => { if (e.key === "Enter" || e.key === " ") setIsFree(prev => !prev); }}
            >
              <div>
                <div className={styles.label}>免费分享</div>
                <small>{isFree ? "已开启" : "关闭后可设置价格"}</small>
              </div>
              <div className={`${styles.switch} ${isFree ? styles.switchOn : ""}`} aria-hidden="true" />
            </div>
          </div>
          <div className={`${styles.field} ${styles.fullWidth}`}>
            <div
              className={styles.toggle}
              role="button"
              tabIndex={0}
              onClick={() => setVisiblePublic(prev => !prev)}
              onKeyDown={(e) => { if (e.key === "Enter" || e.key === " ") setVisiblePublic(prev => !prev); }}
            >
              <div>
                <div className={styles.label}>可见范围</div>
                <small>{visiblePublic ? "公开" : "私密"}</small>
              </div>
              <div className={`${styles.switch} ${visiblePublic ? styles.switchOn : ""}`} aria-hidden="true" />
            </div>
          </div>
        </div>
        <div className={styles.actions}>
          <button type="button" className={styles.submit} onClick={handlePublish} disabled={submitting}>
            {submitting ? "发布中…" : "发布"}
          </button>
        </div>
        {error ? <div className={styles.error}>{error}</div> : null}
        {message ? <div className={styles.success}>{message}</div> : null}
        {previewUrl ? (
          <div className={styles.previewOverlay} onClick={() => setPreviewUrl(null)}>
            <img src={previewUrl} className={styles.previewImage} alt="预览" />
          </div>
        ) : null}
      </div>
    </AppLayout>
  );
};

export default CreatePage;
