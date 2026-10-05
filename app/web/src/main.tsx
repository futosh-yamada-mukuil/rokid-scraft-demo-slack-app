import { render } from "preact";
import { Monitor } from "./Monitor";
import "./style.css";

const root = document.getElementById("app");
if (!root) throw new Error("#app が見つかりません（index.html を確認）");

// 旧 #/config URLを開いても設定QRの生成画面は提供しない。
render(<Monitor />, root);
