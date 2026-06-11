import { BrowserRouter, Route, Routes } from "react-router-dom";
import { Shell } from "./components/Shell";
import { Stub } from "./pages/Stub";
import { Today } from "./pages/Today";

const STUBS: Array<{ path: string; title: string }> = [
  { path: "/plan", title: "Plan" },
  { path: "/recipes", title: "Recipes" },
  { path: "/discover", title: "Discover" },
  { path: "/groceries", title: "Groceries" },
  { path: "/pantry", title: "Pantry" },
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
