"""
Split the unified phishing dataset into URL-static features and dynamic HTML/JS
features. Outputs two CSV files that retain the label column (`status`):

- phishing_data_static.csv
- phishing_data_dynamic.csv

Assumes the source CSV is `phishing_data_tflite_ready.csv` in the same folder.
"""

from pathlib import Path
import pandas as pd

BASE_DIR = Path(__file__).resolve().parent
SOURCE_CSV = BASE_DIR / "phishing_data_tflite_ready.csv"
STATIC_OUT = BASE_DIR / "phishing_data_static.csv"
DYNA_OUT = BASE_DIR / "phishing_data_dynamic.csv"

STATIC_FEATURES = [
    "length_url",
    "length_hostname",
    "ip",
    "nb_dots",
    "nb_hyphens",
    "nb_at",
    "nb_qm",
    "nb_and",
    "nb_or",
    "nb_eq",
    "nb_underscore",
    "nb_tilde",
    "nb_percent",
    "nb_slash",
    "nb_star",
    "nb_colon",
    "nb_comma",
    "nb_semicolumn",
    "nb_dollar",
    "nb_space",
    "nb_www",
    "nb_com",
    "nb_dslash",
    "http_in_path",
    "https_token",
    "ratio_digits_url",
    "ratio_digits_host",
    "punycode",
    "port",
    "tld_in_path",
    "tld_in_subdomain",
    "abnormal_subdomain",
    "nb_subdomains",
    "prefix_suffix",
    "random_domain",
    "shortening_service",
    "path_extension",
    "nb_redirection",
    "nb_external_redirection",
    "length_words_raw",
    "char_repeat",
    "shortest_words_raw",
    "shortest_word_host",
    "shortest_word_path",
    "longest_words_raw",
    "longest_word_host",
    "longest_word_path",
    "avg_words_raw",
    "avg_word_host",
    "avg_word_path",
    "phish_hints",
    "domain_in_brand",
    "brand_in_subdomain",
    "brand_in_path",
    "suspecious_tld",
    "statistical_report",
]

DYNAMIC_FEATURES = [
    "nb_extCSS",
    "ratio_intRedirection",
    "ratio_extRedirection",
    "ratio_intErrors",
    "ratio_extErrors",
    "login_form",
    "submit_email",
    "sfh",
    "iframe",
    "popup_window",
    "onmouseover",
    "right_clic",
    "empty_title",
    "domain_in_title",
    "domain_with_copyright",
]

LABEL_COL = "status"


def ensure_columns(df: pd.DataFrame, columns: list[str]) -> None:
    missing = [c for c in columns if c not in df.columns]
    if missing:
        raise ValueError(f"Missing columns in source CSV: {missing}")


def main() -> None:
    df = pd.read_csv(SOURCE_CSV)
    ensure_columns(df, STATIC_FEATURES + DYNAMIC_FEATURES + [LABEL_COL])

    static_df = df[STATIC_FEATURES + [LABEL_COL]]
    dynamic_df = df[DYNAMIC_FEATURES + [LABEL_COL]]

    static_df.to_csv(STATIC_OUT, index=False)
    dynamic_df.to_csv(DYNA_OUT, index=False)

    print(f"✅ Saved static features to {STATIC_OUT} ({len(static_df.columns)} cols, {len(static_df)} rows)")
    print(f"✅ Saved dynamic features to {DYNA_OUT} ({len(dynamic_df.columns)} cols, {len(dynamic_df)} rows)")


if __name__ == "__main__":
    main()
