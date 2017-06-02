/*
 * Copyright (c) 2015-2017 Hayai Software
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND
 * either express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hayaisoftware.launcher;

import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.support.annotation.LayoutRes;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.ImageView;
import android.widget.TextView;

import com.hayaisoftware.launcher.comparators.AlphabeticalOrder;
import com.hayaisoftware.launcher.comparators.PinToTop;
import com.hayaisoftware.launcher.comparators.RecentOrder;
import com.hayaisoftware.launcher.comparators.UsageOrder;
import com.hayaisoftware.launcher.threading.SimpleTaskConsumerManager;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * This class is an adapter for LaunchableActivities, originally inspired by the ArrayAdapter
 * class.
 */
public class LaunchableAdapter<T extends LaunchableActivity> extends BaseAdapter
        implements Filterable {

    /**
     * This comparator orders {@link LaunchableActivity} objects in alphabetical order.
     */
    private static final Comparator<LaunchableActivity> ALPHABETICAL = new AlphabeticalOrder();

    private static final Pattern DIACRITICAL_MARKS =
            Pattern.compile("\\p{InCombiningDiacriticalMarks}+");

    /**
     * This comparator orders {@link LaunchableActivity} objects with "pins" at the head of the
     * list.
     */
    private static final Comparator<LaunchableActivity> PIN_TO_TOP = new PinToTop();

    /**
     * This comparator orders {@link LaunchableActivity} objects in most recently used at the head
     * of the list.
     */
    private static final Comparator<LaunchableActivity> RECENT = new RecentOrder();

    /**
     * This comparator orders {@link LaunchableActivity} objects in most used order at the head
     * of the list.
     */
    private static final Comparator<LaunchableActivity> USAGE = new UsageOrder();

    /**
     * The context of the parent object.
     */
    private final Context mContext;

    /**
     * The {@link Filter} used by this list {@code Adapter}.
     */
    private final LaunchableFilter mFilter = new LaunchableFilter();

    /**
     * The size of the icons to load, in pixels, if enabled.
     */
    private final int mIconSizePixels;

    private final SimpleTaskConsumerManager mImageLoadingConsumersManager;

    private final ImageLoadingTask.SharedData mImageTasksSharedData;

    /**
     * The {@code LayoutInflater} to use if there is no {@code ConvertView} to use for inflating
     * {@code View}s created by this {@code Adapter}.
     */
    private final LayoutInflater mInflater;

    /**
     * Lock used to modify the content of {@link #mObjects}. Any write operation
     * performed on the array should be synchronized on this lock. This lock is also
     * used by the filter (see {@link #getFilter()} to make a synchronized copy of
     * the original array of data.
     */
    private final Object mLock = new Object();

    /**
     * Contains the list of objects that represent the data of this ArrayAdapter.
     * The content of this list is referred to as "the array" in the documentation.
     */
    private final List<T> mObjects;

    /**
     * This field contains the database used to store persistent values for
     * {@link LaunchableActivity} objects.
     */
    private final LaunchableActivityPrefs mPrefs;

    /**
     * The resource indicating what views to inflate to display the content of this
     * array adapter in a drop down widget.
     */
    private int mDropDownResource;

    /**
     * This field stores whether icons are enabled or disabled.
     */
    private boolean mIconsEnabled = false;

    /**
     * Indicates whether or not {@link #notifyDataSetChanged()} must be called whenever
     * {@link #mObjects} is modified.
     */
    private boolean mNotifyOnChange = false;

    /**
     * This field stores whether to order this list by how recently used the {@link
     * LaunchableActivity} was launched.
     */
    private boolean mOrderByRecent = false;

    /**
     * This field stores whether to order this list by how much the {@link LaunchableActivity} was
     * used.
     */
    private boolean mOrderByUsage = false;

    // A copy of the original mObjects array, initialized from and then used instead as soon as
    // the mFilter ArrayFilter is used. mObjects will then only contain the filtered values.
    private List<T> mOriginalValues;

    /**
     * Constructor
     *
     * @param context  The current context.
     * @param resource The resource ID for a layout file containing a TextView to use when
     *                 instantiating views.
     */
    public LaunchableAdapter(@NonNull final Context context, @LayoutRes final int resource, final int initialSize) {
        final Resources res = context.getResources();
        mContext = context;
        mInflater = LayoutInflater.from(context);
        mDropDownResource = resource;
        mObjects = Collections.synchronizedList(new ArrayList<T>(initialSize));
        mIconSizePixels = res.getDimensionPixelSize(R.dimen.app_icon_size);
        mImageLoadingConsumersManager =
                new SimpleTaskConsumerManager(getOptimalNumberOfThreads(res), 300);
        mImageTasksSharedData = new ImageLoadingTask.SharedData((Activity) mContext, mContext,
                mIconSizePixels);
        mPrefs = new LaunchableActivityPrefs(context);
    }

    /**
     * This constructor is for reloading this Adapter using the {@link #export()} method.
     *
     * @param object   The Object from the {@code export()} method.
     * @param context  The current context.
     * @param resource The resource ID for a layout file containing a TextView to use when
     *                 instantiating views.
     */
    @SuppressWarnings("unchecked")
    public LaunchableAdapter(final Object object, @NonNull final Context context,
            @LayoutRes final int resource) {
        this(context, resource, ((List<? extends T>[]) object)[0].size());

        final List<? extends T>[] lists = (List<? extends T>[]) object;
        mObjects.addAll(lists[0]);

        if (lists[1] != null) {
            mOriginalValues = (List<T>) lists[1];
        }
    }

    private static int getOptimalNumberOfThreads(final Resources resources) {
        final int numOfCores = Runtime.getRuntime().availableProcessors();
        final int maxThreads = resources.getInteger(R.integer.max_imageloading_threads);
        int numThreads = numOfCores - 1;

        //clamp numThreads
        if (numThreads < 1) {
            numThreads = 1;
        } else if (numThreads > maxThreads) {
            numThreads = maxThreads;
        }

        return numThreads;
    }

    /**
     * Adds the specified object at the end of the array.
     *
     * @param object The object to add at the end of the array.
     */
    public void add(@Nullable final T object) {
        mPrefs.setPreferences(object);

        synchronized (mLock) {
            if (mOriginalValues == null) {
                mObjects.add(object);
            } else {
                mOriginalValues.add(object);
            }
        }
        if (mNotifyOnChange) {
            notifyDataSetChanged();
        }
    }

    /**
     * Adds the specified Collection at the end of the array.
     *
     * @param collection The Collection to add at the end of the array.
     * @throws UnsupportedOperationException if the <tt>addAll</tt> operation
     *                                       is not supported by this list
     * @throws ClassCastException            if the class of an element of the specified
     *                                       collection prevents it from being added to this list
     * @throws NullPointerException          if the specified collection contains one
     *                                       or more null elements and this list does not permit
     *                                       null elements, or if the specified collection is null
     * @throws IllegalArgumentException      if some property of an element of the
     *                                       specified collection prevents it from being added to
     *                                       this list
     */
    public void addAll(@NonNull final Collection<? extends T> collection) {
        synchronized (mLock) {
            if (mOriginalValues == null) {
                mObjects.addAll(collection);
            } else {
                mOriginalValues.addAll(collection);
            }
        }
        if (mNotifyOnChange) {
            notifyDataSetChanged();
        }
    }

    /**
     * Adds the specified items at the end of the array.
     *
     * @param items The items to add at the end of the array.
     */
    public void addAll(final T... items) {
        synchronized (mLock) {
            if (mOriginalValues == null) {
                Collections.addAll(mObjects, items);
            } else {
                Collections.addAll(mOriginalValues, items);
            }
        }
        if (mNotifyOnChange) {
            notifyDataSetChanged();
        }
    }

    /**
     * Remove all elements from the list.
     */
    public void clear() {
        synchronized (mLock) {
            if (mOriginalValues == null) {
                mObjects.clear();
            } else {
                mOriginalValues.clear();
            }
        }
        if (mNotifyOnChange) {
            notifyDataSetChanged();
        }
    }

    public void clearCaches() {
        for (final LaunchableActivity activity : mObjects) {
            activity.deleteActivityIcon();
        }

        if (mOriginalValues != null) {
            for (final LaunchableActivity activity : mOriginalValues) {
                activity.deleteActivityIcon();
            }
        }
    }

    /**
     * This method disables ordering by recent use of a {@link LaunchableActivity}.
     */
    public void disableOrderByRecent() {
        mOrderByRecent = false;
    }

    /**
     * This method disables ordering by total number of uses of a {@link LaunchableActivity}.
     */
    public void disableOrderByUsage() {
        mOrderByUsage = false;
    }

    /**
     * This method enables ordering by recent use of a {@link LaunchableActivity}.
     */
    public void enableOrderByRecent() {
        mOrderByRecent = true;
    }

    /**
     * This method enables ordering by total number of uses of a {@link LaunchableActivity}.
     */
    public void enableOrderByUsage() {
        mOrderByUsage = true;
    }

    /**
     * The Object from this method is for use with
     * {@link Activity#onRetainNonConfigurationInstance()} and
     * {@link LaunchableAdapter#LaunchableAdapter(Object, Context, int)}.
     *
     * @return An object used to restore the state of this Adapter.
     */
    public Object export() {
        return new List<?>[]{mObjects, mOriginalValues};
    }

    /**
     * Returns the context associated with this array adapter. The context is used
     * to create views from the resource passed to the constructor.
     *
     * @return The Context associated with this adapter.
     */
    @NonNull
    public Context getContext() {
        return mContext;
    }

    @Override
    public int getCount() {
        return mObjects.size();
    }

    @Override
    public View getDropDownView(final int position, @Nullable final View convertView,
            @NonNull final ViewGroup parent) {
        final TextView text;

        if (convertView == null) {
            text = (TextView) mInflater.inflate(mDropDownResource, parent, false);
        } else {
            text = (TextView) convertView;
        }

        text.setText(getItem(position).toString());

        return text;
    }

    /**
     * <p>Returns a filter that can be used to constrain data with a filtering
     * pattern.</p>
     * <p>
     * <p>This method is usually implemented by {@link android.widget.Adapter}
     * classes.</p>
     *
     * @return a filter used to constrain data
     */
    @NonNull
    @Override
    public Filter getFilter() {
        return mFilter;
    }

    /**
     * This method returns the {@link LaunchableActivity} found at the {@code position} in this
     * adapter.
     * <p>
     * The position is not altered by the current {@link Filter} in use.
     *
     * @param position The index of the {@code LaunchableActivity} to return.
     * @return The {@code LaunchableActivity} in the position, null otherwise.
     * @throws IndexOutOfBoundsException if the index is out of range
     *                                   (<tt>index &lt; 0 || index &gt;= size()</tt>)
     */
    @Nullable
    @Override
    public T getItem(final int position) {
        return mObjects.get(position);
    }

    /**
     * This returns the position given.
     *
     * @param position The position given.
     * @return The position from the {@code position} argument.
     */
    @Override
    public long getItemId(final int position) {
        return position;
    }

    /**
     * Returns the position of a {@link LaunchableActivity} where the
     * {@link LaunchableActivity#getClassName()} is equal to the {@code className} parameter.
     *
     * @param className The classname to find.
     * @return The LaunchableActivity matching the classname parameter, {@code -1} if not found.
     */
    public int getPosition(@NonNull final String className) {
        final List<T> current;
        int position = -1;

        if (mOriginalValues == null) {
            current = mObjects;
        } else {
            current = mOriginalValues;
        }

        for (int i = 0; i < current.size(); i++) {
            if (className.equals(current.get(i).getClassName())) {
                position = i;
                break;
            }
        }

        return position;
    }

    /**
     * The {@link View} used as a grid item for the LaunchableAdapter {@code GridView}.
     *
     * @param position    The position to of the {@link LaunchableActivity} to return a {@code
     *                    View} for.
     * @param convertView The old {@code View} to reuse, if possible.
     * @param parent      The parent {@code View}.
     * @return The {@code View} to use in the {@code GridView}.
     */
    @NonNull
    @Override
    public View getView(final int position, final View convertView,
            @NonNull final ViewGroup parent) {
        final View view;
        if (convertView == null) {
            view = mInflater.inflate(R.layout.app_grid_item, parent, false);
        } else {
            view = convertView;
        }

        view.setVisibility(View.VISIBLE);
        final LaunchableActivity launchableActivity = getItem(position);
        final CharSequence label = launchableActivity.getActivityLabel();
        final TextView appLabelView = view.findViewById(R.id.appLabel);
        final ImageView appIconView = view.findViewById(R.id.appIcon);
        final View appShareIndicator = view.findViewById(R.id.appShareIndicator);
        final View appPinToTop = view.findViewById(R.id.appPinToTop);

        appLabelView.setText(label);

        appIconView.setTag(launchableActivity);
        if (launchableActivity.isIconLoaded()) {
            appIconView.setImageDrawable(
                    launchableActivity.getActivityIcon(mContext, mIconSizePixels));
        } else {
            if (mIconsEnabled) {
                mImageLoadingConsumersManager.addTask(
                        new ImageLoadingTask(appIconView, launchableActivity,
                                mImageTasksSharedData));
            }
        }

        if (launchableActivity.isShareable()) {
            appShareIndicator.setVisibility(View.VISIBLE);
        } else {
            appShareIndicator.setVisibility(View.GONE);
        }

        if (launchableActivity.getPriority() > 0) {
            appPinToTop.setVisibility(View.VISIBLE);
        } else {
            appPinToTop.setVisibility(View.GONE);
        }

        return view;
    }

    /**
     * Inserts the specified object at the specified index in the array.
     *
     * @param object The object to insert into the array.
     * @param index  The index at which the object must be inserted.
     */
    public void insert(@Nullable final T object, final int index) {
        mPrefs.setPreferences(object);

        synchronized (mLock) {
            if (mOriginalValues == null) {
                mObjects.add(index, object);
            } else {
                mOriginalValues.add(index, object);
            }
        }
        if (mNotifyOnChange) {
            notifyDataSetChanged();
        }
    }

    /**
     * Notifies the attached observers that the underlying data has been changed
     * and any View reflecting the data set should refresh itself.
     */
    @Override
    public void notifyDataSetChanged() {
        super.notifyDataSetChanged();
        mNotifyOnChange = true;
    }

    /**
     * This method should be called before the parent context is destroyed.
     */
    public void onDestroy() {
        mPrefs.close();

        if (mImageLoadingConsumersManager != null) {
            mImageLoadingConsumersManager.destroyAllConsumers(false);
        }
    }

    /**
     * This method removes all applications belonging to a package name.
     *
     * @param packageName The package name to remove.
     * @return Whether the adapter was modified.
     */
    public boolean remove(@NonNull final String packageName) {
        final int initCount;
        final int resultCount;
        final Iterator<T> iter;

        synchronized (mLock) {
            if (mOriginalValues == null) {
                iter = mObjects.listIterator();
                initCount = mObjects.size();
            } else {
                iter = mOriginalValues.listIterator();
                initCount = mOriginalValues.size();
            }

            while (iter.hasNext()) {
                if (iter.next().getClassName().startsWith(packageName)) {
                    iter.remove();
                }
            }

            if (mOriginalValues == null) {
                resultCount = mObjects.size();
            } else {
                resultCount = mOriginalValues.size();
            }
        }

        if (mNotifyOnChange) {
            notifyDataSetChanged();
        }

        return initCount != resultCount;
    }

    /**
     * Removes the specified object from the array.
     *
     * @param object The object to remove.
     */
    public void remove(@Nullable final T object) {
        synchronized (mLock) {
            if (mOriginalValues == null) {
                mObjects.remove(object);
            } else {
                mOriginalValues.remove(object);
            }
        }

        if (mNotifyOnChange) {
            notifyDataSetChanged();
        }
    }

    /**
     * <p>Sets the layout resource to create the drop down views.</p>
     *
     * @param resource the layout resource defining the drop down views
     * @see #getDropDownView(int, View, ViewGroup)
     */
    public void setDropDownViewResource(@LayoutRes final int resource) {
        mDropDownResource = resource;
    }

    /**
     * This method disables loading of {@link LaunchableActivity} icons.
     */
    public void setIconsDisabled() {
        mIconsEnabled = false;
    }

    /**
     * This method disables loading of {@link LaunchableActivity} icons.
     */
    public void setIconsEnabled() {
        mIconsEnabled = true;
    }

    /**
     * Control whether methods that change the list ({@link #add}, {@link #addAll(Collection)},
     * {@link #addAll(LaunchableActivity[])} (Object[])}, {@link #insert}, {@link #remove},
     * {@link #clear}, {@link #sort(Comparator)}) automatically call {@link #notifyDataSetChanged}.
     * If set to false, caller must manually call notifyDataSetChanged() to have the changes
     * reflected in the attached view.
     * <p>
     * The default is true, and calling notifyDataSetChanged()
     * resets the flag to true.
     *
     * @param notifyOnChange if true, modifications to the list will
     *                       automatically call {@link
     *                       #notifyDataSetChanged}
     */
    public void setNotifyOnChange(final boolean notifyOnChange) {
        mNotifyOnChange = notifyOnChange;
    }

    /**
     * Sorts the content of this adapter using the specified comparator.
     *
     * @param comparator The comparator used to sort the objects contained
     *                   in this adapter.
     */
    public void sort(@NonNull final Comparator<? super T> comparator) {
        synchronized (mLock) {
            Collections.sort(mObjects, comparator);
            if (mOriginalValues != null) {
                Collections.sort(mOriginalValues, comparator);
            }
        }

        if (mNotifyOnChange) {
            notifyDataSetChanged();
        }
    }

    /**
     * This method sorts all {@link LaunchableActivity} objects in this {@code Adapter}.
     *
     * @see #disableOrderByRecent()
     * @see #enableOrderByRecent()
     * @see #disableOrderByUsage()
     * @see #enableOrderByUsage()
     */
    public void sortApps() {
        synchronized (mLock) {
            final boolean notify = mNotifyOnChange;
            mNotifyOnChange = false;

            sort(ALPHABETICAL);

            if (mOrderByRecent) {
                sort(RECENT);
            } else if (mOrderByUsage) {
                sort(USAGE);
            }

            sort(PIN_TO_TOP);

            if (notify) {
                notifyDataSetChanged();
            }
        }
    }

    /*
     * Returns a string representation of the current LaunchActivity collection.
     *
             * @return A string representation of the current LaunchActivity collection.
            */
    @Override
    public String toString() {
        final String toString;

        if (mOriginalValues == null) {
            toString = mObjects.toString();
        } else {
            toString = mOriginalValues.toString();
        }

        return toString;
    }

    /**
     * <p>An array filter constrains the content of the array adapter with
     * a prefix. Each item that does not start with the supplied prefix
     * is removed from the list.</p>
     */
    private final class LaunchableFilter extends Filter {

        @Override
        protected FilterResults performFiltering(final CharSequence constraint) {
            final List<T> values;
            final FilterResults results = new FilterResults();

            // Don't act upon a blank constraint if the filter hasn't been used yet.
            if (mOriginalValues == null && constraint.length() == 0) {
                results.values = mObjects;
                results.count = mObjects.size();
            } else {
                if (mOriginalValues == null) {
                    synchronized (mLock) {
                        mOriginalValues = new ArrayList<>(mObjects);
                    }
                }

                synchronized (mLock) {
                    values = new ArrayList<>(mOriginalValues);
                }
                final int count = values.size();
                if (constraint == null || constraint.length() == 0) {
                    results.values = values;
                    results.count = count;
                } else {
                    final String prefixString = stripAccents(constraint).toLowerCase();
                    final Collection<T> newValues = new ArrayList<>();

                    for (int i = 0; i < count; i++) {
                        final T value = values.get(i);

                        if (value.toString().toLowerCase().contains(prefixString)) {
                            newValues.add(value);
                        }
                    }

                    results.values = newValues;
                    results.count = newValues.size();
                }
            }

            return results;
        }

        @Override
        protected void publishResults(final CharSequence constraint, final FilterResults results) {
            //noinspection ObjectEquality
            if (mObjects != results.values) {
                mObjects.clear();
                //noinspection unchecked
                mObjects.addAll((Collection<T>) results.values);

                if (results.count > 0) {
                    notifyDataSetChanged();
                } else {
                    notifyDataSetInvalidated();
                }
            }
        }

        private String stripAccents(final CharSequence cs) {
            return DIACRITICAL_MARKS.matcher(
                    Normalizer.normalize(cs, Normalizer.Form.NFKD)).replaceAll("");
        }
    }
}
