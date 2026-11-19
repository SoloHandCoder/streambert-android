import "./mobile/electronShim.js";
import React from "react";
import ReactDOM from "react-dom/client";
import App from "./App";
import "./styles/global.css";
import "./styles/mobile.css";
import { initTvNav } from "./mobile/tvNav.js";

initTvNav();

ReactDOM.createRoot(document.getElementById("root")).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
