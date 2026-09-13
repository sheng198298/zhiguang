import { Navigate, Route, Routes } from "react-router-dom";
import HomePage from "./pages/HomePage";
import HotListPage from "./pages/HotListPage";
import SearchPage from "./pages/SearchPage";
import CreatePage from "./pages/CreatePage";
import LearningPage from "./pages/LearningPage";
import ProfilePage from "./pages/ProfilePage";
import EditProfilePage from "./pages/EditProfilePage";
import CourseDetailPage from "./pages/CourseDetailPage";
import LoginPage from "./pages/LoginPage";
import RegisterPage from "./pages/RegisterPage";

function App() {
  return (
    <Routes>
      <Route path="/" element={<HomePage />} />
      <Route path="/hot" element={<HotListPage />} />
      <Route path="/search" element={<SearchPage />} />
      <Route path="/create" element={<CreatePage />} />
      <Route path="/learn" element={<LearningPage />} />
      <Route path="/profile" element={<ProfilePage />} />
      <Route path="/profile/edit" element={<EditProfilePage />} />
      <Route path="/post/:id" element={<CourseDetailPage />} />
      <Route path="/login" element={<LoginPage />} />
      <Route path="/register" element={<RegisterPage />} />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}

export default App;
