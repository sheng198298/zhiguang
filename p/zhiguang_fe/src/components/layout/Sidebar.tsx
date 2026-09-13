import { NavLink } from "react-router-dom";
import { BookIcon, CreateIcon, HomeIcon, HotIcon, ProfileIcon, SearchIcon, StudyIcon } from "@/components/icons/Icon";
import styles from "./Sidebar.module.css";

const navItems = [
  { to: "/", label: "首页", Icon: HomeIcon },
  { to: "/hot", label: "热榜", Icon: HotIcon },
  { to: "/search", label: "搜索", Icon: SearchIcon },
  { to: "/create", label: "创作", Icon: CreateIcon },
  { to: "/learn", label: "学习", Icon: StudyIcon },
  { to: "/profile", label: "我的", Icon: ProfileIcon }
] as const;

const Sidebar = () => {
  return (
    <aside className={styles.sidebar}>
      <div className={styles.logo}>
        <BookIcon width={30} height={30} stroke="none" fill="#fff" />
      </div>
      <nav className={styles.nav}>
        {navItems.map(({ to, label, Icon }) => (
          <NavLink
            key={to}
            to={to}
            end={to === "/"}
            className={({ isActive }) => (isActive ? `${styles.link} ${styles.linkActive}` : styles.link)}
          >
            <Icon />
            {label}
          </NavLink>
        ))}
      </nav>
      <div className={styles.divider} />
      <div className={styles.footer}>
        <span>博客</span>
        <div>分享知识</div>
      </div>
    </aside>
  );
};

export default Sidebar;
