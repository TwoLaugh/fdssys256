import { BrowserRouter, Route, Routes } from "react-router-dom";
import { Shell } from "./components/Shell";
import { Activity } from "./pages/Activity";
import { Admin } from "./pages/Admin";
import { Discover } from "./pages/Discover";
import { Groceries } from "./pages/Groceries";
import { InviteAccept } from "./pages/InviteAccept";
import { Login } from "./pages/Login";
import { Notifications } from "./pages/Notifications";
import { Nutrition } from "./pages/Nutrition";
import { Onboarding } from "./pages/Onboarding";
import { Pantry } from "./pages/Pantry";
import { Plan } from "./pages/Plan";
import { PlanGenerate } from "./pages/PlanGenerate";
import { Preferences } from "./pages/Preferences";
import { RecipeDetail } from "./pages/RecipeDetail";
import { Recipes } from "./pages/Recipes";
import { Settings } from "./pages/Settings";
import { Stub } from "./pages/Stub";
import { Today } from "./pages/Today";

export function App() {
  return (
    <BrowserRouter>
      <Routes>
        {/* Full-screen surfaces outside the shell (no rail). */}
        <Route path="/login" element={<Login />} />
        <Route path="/onboarding" element={<Onboarding />} />
        <Route element={<Shell />}>
          <Route index element={<Today />} />
          <Route path="/plan" element={<Plan />} />
          <Route path="/plan/generate" element={<PlanGenerate />} />
          <Route path="/recipes" element={<Recipes />} />
          <Route path="/recipes/:id" element={<RecipeDetail />} />
          <Route path="/discover" element={<Discover />} />
          <Route path="/groceries" element={<Groceries />} />
          <Route path="/pantry" element={<Pantry />} />
          <Route path="/nutrition" element={<Nutrition />} />
          <Route path="/preferences" element={<Preferences />} />
          <Route path="/activity" element={<Activity />} />
          <Route path="/notifications" element={<Notifications />} />
          <Route path="/settings" element={<Settings />} />
          {/* Invite accept: its own deep-link surface (settings.md §3d). */}
          <Route path="/invite" element={<InviteAccept />} />
          <Route path="/admin" element={<Admin />} />
          <Route path="*" element={<Stub title="Not found" route="404" />} />
        </Route>
      </Routes>
    </BrowserRouter>
  );
}
