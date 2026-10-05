package de.schladebeck.fx.grid.internal;

import java.util.Arrays;
import java.util.Objects;

/// Maps view indices to model indices for [ViewRows]. The identity mapping needs no arrays; the inverse is built
/// on first use. A view may show fewer indices than the model has (filtered rows).
///
/// The view array has room to spare, like an `ArrayList`, so inserting a few indices moves part of it instead of
/// copying all of it into a new array. Live feeds insert a few rows many times per second.
public final class IndexMap {

    private int modelCount;
    /** The model index at each view index, in the first {@link #count} entries; {@code null} for the identity. */
    private int[] viewToModel;
    private int count;
    private int[] modelToView;

    public void setIdentity(int modelCount) {
        this.modelCount = modelCount;
        this.viewToModel = null;
        this.modelToView = null;
    }

    /** {@code viewToModel[view]} is the model index shown at {@code view}; each model index at most once. */
    public void set(int[] viewToModel, int modelCount) {
        this.modelCount = modelCount;
        this.viewToModel = viewToModel;
        this.count = viewToModel.length;
        this.modelToView = null;
    }

    /// Model indices `first` to `first + count - 1` were inserted: later model indices move up. The new ones
    /// are not shown until [#insert] adds them.
    public void modelInserted(int first, int count) {
        boolean appended = first >= modelCount;
        modelCount += count;
        modelToView = null;
        if (viewToModel != null && !appended) {
            for (int view = 0; view < this.count; view++) {
                if (viewToModel[view] >= first) {
                    viewToModel[view] += count;
                }
            }
        }
    }

    /// Model indices `first` to `first + count - 1` were removed: their view indices go, later model indices
    /// move down.
    public void modelRemoved(int first, int count) {
        modelCount -= count;
        modelToView = null;
        if (viewToModel == null) {
            return;
        }
        int kept = 0;
        for (int view = 0; view < this.count; view++) {
            int model = viewToModel[view];
            if (model < first) {
                viewToModel[kept++] = model;
            } else if (model >= first + count) {
                viewToModel[kept++] = model - count;
            }
        }
        this.count = kept;
    }

    /// Shows model index `models[i]` in front of the current view index `before[i]`, for ascending `before`.
    /// Not for the identity.
    public void insert(int[] before, int[] models) {
        int k = models.length;
        if (count + k > viewToModel.length) {
            viewToModel = Arrays.copyOf(viewToModel, Math.max(count + k, count + (count >> 1)));
        }
        // From the back, so each block moves once: the block from before[i] on moves up by the i + 1 new indices
        // in front of it.
        int end = count;
        for (int i = k - 1; i >= 0; i--) {
            System.arraycopy(viewToModel, before[i], viewToModel, before[i] + i + 1, end - before[i]);
            viewToModel[before[i] + i] = models[i];
            end = before[i];
        }
        count += k;
        modelToView = null;
    }

    public boolean isIdentity() {
        return viewToModel == null;
    }

    /** Whether {@link #toView} is a plain array access, without first building the inverse. */
    public boolean hasInverse() {
        return modelToView != null;
    }

    /** Number of view indices. */
    public int count() {
        return viewToModel == null ? modelCount : count;
    }

    /** Number of model indices. */
    public int modelCount() {
        return modelCount;
    }

    public int toModel(int view) {
        return viewToModel == null ? view : viewToModel[Objects.checkIndex(view, count)];
    }

    /** View index of a model index, or -1 if it is not shown. */
    public int toView(int model) {
        if (viewToModel == null) {
            return model < modelCount ? model : -1;
        }
        if (modelToView == null) {
            modelToView = new int[modelCount];
            Arrays.fill(modelToView, -1);
            for (int view = 0; view < count; view++) {
                modelToView[viewToModel[view]] = view;
            }
        }
        return model < modelToView.length ? modelToView[model] : -1;
    }
}
