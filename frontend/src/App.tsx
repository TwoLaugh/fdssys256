import { BrowserRouter, Route, Routes } from "react-router-dom";
import { Shell } from "./components/Shell";
import { Groceries } from "./pages/Groceries";
import { Pantry } from "./pages/Pantry";
import { Plan } from "./pages/Plan";
import { PlanGenerate } from "./pages/PlanGenerate";
import { RecipeDetail } from "./pages/RecipeDetail";
import { Recipes } from "./pages/Recipes";
import { Stub } from "./pages/Stub";
import { Today } from "./pages/Today";

const STUBS: Array<{ path: string; title: string }> = [
  { path: "/discover", title: "Discover" },
  { path: "/nutrition", title: "Nutrition" },
  { path: "/preferences", title: "Preferences" },
  { path: "/activity", title: "Activity" },
  { path: "/notifications", title: "Notifications" },
  { path: "/settings", title: "Settings" },
];

export function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route element={<Shell />}>
          <Route index element={<Today />} />
          <Route path="/plan" element={<Plan />} />
          <Route path="/plan/generate" element={<PlanGenerate />} />
          <Route path="/recipes" element={<Recipes />} />
          <Route path="/recipes/:id" element={<RecipeDetail />} />
          <Route path="/groceries" element={<Groceries />} />
          <Route path="/pantry" element={<Pantry />} />
          {STUBS.map(({ path, title }) => (
            <Route
              key={path}
              path={path}
              element={<Stub title={title} route={path} />}
            />
          ))}
          <Route path="*" element={<Stub title="Not found" route="404" />} />
        </Route>
      </Routes>
    </BrowserRouter>
  );
}
