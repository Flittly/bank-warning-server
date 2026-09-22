"""Extract all text from a PDF using pypdf.

Usage: uv run python extract_text.py <path-to-pdf>
Output: plain text to stdout
"""

import sys
from pypdf import PdfReader


def main() -> None:
    if len(sys.argv) < 2:
        print("Usage: extract_text.py <pdf-path>", file=sys.stderr)
        sys.exit(1)

    path = sys.argv[1]
    reader = PdfReader(path)
    pages = []
    for page in reader.pages:
        text = page.extract_text()
        if text:
            pages.append(text)
    print("\n\n".join(pages))


if __name__ == "__main__":
    main()
