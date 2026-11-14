"""Semantic slide compositions. Every mark is an editable native SVG object.

The caller supplies the same bounded text renderer used by legacy decks. Long
copy must fail the layout gate, not become unreadable or disappear off-canvas.
"""


def designed_slide(deck, slide, index, text, font):
    navy = deck.theme == "navy"
    bg, ink, muted, accent, surface, line = (
        ("#0B192B", "#F5F8FC", "#A9BDD2", "#60DDCC", "#142A42", "#29445D") if navy
        else ("#F7F5EF", "#192C35", "#596B73", "#167B70", "#FFFFFF", "#D5DEDA")
    )
    out = [f'<svg xmlns="http://www.w3.org/2000/svg" width="1280" height="720" viewBox="0 0 1280 720" font-family="{font}" font-size="24" lang="{deck.language}">']

    def rect(x, y, w, h, color, radius=0):
        out.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{radius}" fill="{color}"/>')

    def label(value, x, y, size, color=ink, width=80, lines=1, bold=False):
        if value:
            out.append(text(value, x, y, size, color, width, lines, bold))

    rect(0, 0, 1280, 720, bg)
    if slide.layout == "cover":
        # A quiet, editable visual anchor gives the cover a deliberate focal
        # point without inventing a product screenshot or an unsupported claim.
        out.extend([
            f'<circle cx="1050" cy="282" r="190" fill="{surface}" opacity="0.9"/>',
            f'<circle cx="1050" cy="282" r="136" fill="none" stroke="{line}" stroke-width="2"/>',
            f'<circle cx="1050" cy="282" r="82" fill="none" stroke="{accent}" stroke-width="3" opacity="0.8"/>',
            f'<circle cx="1130" cy="188" r="12" fill="{accent}"/>',
            f'<circle cx="941" cy="368" r="8" fill="{accent}" opacity="0.7"/>',
        ])
        label(slide.kicker or deck.title, 64, 68, 16, accent, 100, 2, True)
        label(slide.title, 64, 260, 64, ink, 32, 3, True)
        label(slide.subtitle, 68, 488, 24, muted, 84, 2)
        for i, bullet in enumerate(slide.bullets):
            label(bullet, 68, 574 + i * 42, 20, muted, 106, 1)
        if slide.takeaway:
            label(slide.takeaway, 64, 666, 16, accent, 120, 1)
        label(f'{index + 1:02d} / {len(deck.slides):02d}', 1140, 690, 12, muted, 12, 1)
        out.append('</svg>')
        return ''.join(out)
    kicker = slide.kicker or f'{index + 1:02d}'
    label(kicker, 64, 56, 15, accent, 56, 1, True)
    label(slide.title, 64, 126, 40, ink, 56, 2, True)
    label(slide.subtitle, 64, 222, 20, muted, 104, 2)

    if slide.layout in ("bullets", "columns"):
        for i, bullet in enumerate(slide.bullets):
            if slide.layout == "columns":
                x, y = 64 + i % 2 * 588, 282 + i // 2 * 148
                rect(x, y, 4, 112, accent)
                label(bullet, x + 24, y + 24, 20, ink, 50, 4)
            else:
                y = 288 + i * 60
                label(f'{i + 1:02d}', 64, y, 16, accent, 4, 1, True)
                label(bullet, 116, y, 21, ink, 96, 2)
    elif slide.layout in ("cards", "process", "comparison"):
        for i, block in enumerate(slide.blocks):
            if slide.layout == "cards":
                x, y = 64 + (i % 2) * 588, 280 + (i // 2) * 148
                rect(x, y, 564, 132, surface, 12)
                label(f'{i + 1:02d}', x + 20, y + 30, 14, accent, 4, 1, True)
                label(block.heading, x + 62, y + 32, 20, ink, 44, 2, True)
                label(block.body, x + 62, y + 81, 17, muted, 52, 2)
            elif slide.layout == "comparison":
                x = 64 + i * 588
                rect(x, 280, 564, 292, surface, 12)
                rect(x, 280, 564, 4, accent if i else line)
                label(block.heading, x + 28, 332, 26, ink, 36, 2, True)
                label(block.body, x + 28, 426, 22, muted, 42, 4)
            else:
                count = len(slide.blocks)
                width = (1152 - (count - 1) * 24) / count
                x = 64 + i * (width + 24)
                # Connected steps encode sequence, not a decorative background.
                if i + 1 < count:
                    rect(x + 42, 307, width, 2, line)
                out.append(f'<circle cx="{x + 20}" cy="308" r="20" fill="{accent}"/>')
                label(str(i + 1), x + 14, 315, 18, bg, 3, 1, True)
                label(block.heading, x, 378, 23, ink, int(width / 12), 2, True)
                label(block.body, x, 456, 20, muted, int(width / 10.5), 4)
    elif slide.layout == "chart":
        points = slide.chart.points if slide.chart else []
        maximum = max((point.value for point in points), default=1) or 1
        for i, point in enumerate(points):
            y = 292 + i * 52
            label(point.label, 64, y + 24, 18, ink, 24, 1)
            rect(314, y, 686, 34, surface, 4)
            rect(314, y, 686 * point.value / maximum, 34, accent, 4)
            label(f'{point.value:g}{slide.chart.unit}', 1024, y + 24, 18, ink, 18, 1, True)
        label(slide.chart.caption if slide.chart else "", 64, 585, 15, muted, 145, 2)
    elif slide.layout == "closing":
        for i, bullet in enumerate(slide.bullets):
            y = 306 + i * 84
            label(f'{i + 1:02d}', 64, y, 20, accent, 4, 1, True)
            label(bullet, 126, y, 26, ink, 76, 2)
    if slide.takeaway:
        rect(64, 612, 1152, 54, surface, 8)
        label(slide.takeaway, 84, 634, 17, accent, 126, 2, True)
    rect(64, 675, 1152, 1, line)
    label(deck.title[:55], 64, 699, 12, muted, 150, 1)
    label(f'{index + 1:02d} / {len(deck.slides):02d}', 1140, 699, 12, muted, 12, 1)
    out.append('</svg>')
    return ''.join(out)
