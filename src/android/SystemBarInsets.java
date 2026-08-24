package com.nfcdocumentreader;

import android.view.View;

import androidx.core.graphics.Insets;
import androidx.core.view.OnApplyWindowInsetsListener;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * Keeps the camera overlays clear of the status bar, navigation bar and display cutout.
 *
 * The camera screens deliberately draw the preview edge to edge — a letterboxed preview with
 * black bars looks broken — so the overlay chrome on top of it has to be inset by hand. Without
 * this the title collides with the clock and the Cancel button sits under the navigation bar,
 * which is what the screens did.
 *
 * Insets are applied as padding on the two bars only, never on the root: padding the root would
 * shrink the preview itself. Values are always computed from the paddings the layout declared, so
 * repeated inset callbacks cannot accumulate.
 *
 * On a window the system already insets, the reported insets are zero and this is a no-op — which
 * is why it is safe regardless of how the host app's theme handles edge-to-edge.
 */
final class SystemBarInsets {

    private SystemBarInsets() {
    }

    /**
     * @param root         view to observe insets on, normally android.R.id.content
     * @param topBar       chrome pinned to the top edge, or null
     * @param bottomPanel  chrome pinned to the bottom edge, or null
     */
    static void apply(View root, final View topBar, final View bottomPanel) {
        if (root == null) return;

        final int[] topBase = basePadding(topBar);
        final int[] bottomBase = basePadding(bottomPanel);

        ViewCompat.setOnApplyWindowInsetsListener(root, new OnApplyWindowInsetsListener() {
            @Override
            public WindowInsetsCompat onApplyWindowInsets(View view, WindowInsetsCompat insets) {
                Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                        | WindowInsetsCompat.Type.displayCutout());

                if (topBar != null) {
                    topBar.setPadding(topBase[0] + bars.left,
                            topBase[1] + bars.top,
                            topBase[2] + bars.right,
                            topBase[3]);
                }
                if (bottomPanel != null) {
                    bottomPanel.setPadding(bottomBase[0] + bars.left,
                            bottomBase[1],
                            bottomBase[2] + bars.right,
                            bottomBase[3] + bars.bottom);
                }
                // Not consumed: children still see the insets.
                return insets;
            }
        });

        // The first inset dispatch may already have happened by the time a listener is attached.
        ViewCompat.requestApplyInsets(root);
    }

    private static int[] basePadding(View view) {
        if (view == null) return new int[] {0, 0, 0, 0};
        // Left/right rather than start/end: these are the already-resolved values, so the layout's
        // paddingStart survives in both directions.
        return new int[] {
                view.getPaddingLeft(),
                view.getPaddingTop(),
                view.getPaddingRight(),
                view.getPaddingBottom()
        };
    }
}
