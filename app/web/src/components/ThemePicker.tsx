import { useEffect, useState } from "preact/hooks";

/** style.css の @plugin "daisyui" で有効化したものと合わせること。 */
const THEMES = [
  "black",
  "dim",
  "night",
  "dark",
  "sunset",
  "dracula",
  "business",
  "light",
] as const;

const KEY = "scraft-theme";

/**
 * daisyUI のテーマ切替。<html data-theme> を書き換えるだけ。
 * 展示会の会場照明に合わせて現地で変えられるよう、選択は localStorage に残す。
 */
export function ThemePicker() {
  const [theme, setTheme] = useState<string>(
    () => localStorage.getItem(KEY) ?? "black",
  );

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    localStorage.setItem(KEY, theme);
  }, [theme]);

  return (
    <select
      className="select select-xs select-ghost w-28"
      value={theme}
      onChange={(e) => setTheme((e.target as HTMLSelectElement).value)}
      title="テーマ"
    >
      {THEMES.map((t) => (
        <option key={t} value={t}>
          {t}
        </option>
      ))}
    </select>
  );
}
