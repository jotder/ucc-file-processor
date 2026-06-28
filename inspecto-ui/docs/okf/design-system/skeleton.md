---
type: Component
title: Skeleton
description: Loading-placeholder primitive for labels, paragraphs, and blocks.
resource: inspecto-ui/src/app/inspecto/components/skeleton.component.ts
tags: [design-system, skeleton, loading]
timestamp: 2026-06-28T00:00:00Z
---

# Skeleton

`<inspecto-skeleton>` renders shimmer loading placeholders while data loads, respecting
`prefers-reduced-motion`.

# Examples

```html
<inspecto-skeleton width="40%" height="0.875rem" />   <!-- a label -->
<inspecto-skeleton [lines]="4" />                      <!-- a paragraph -->
<inspecto-skeleton height="12rem" />                   <!-- a block -->
```
