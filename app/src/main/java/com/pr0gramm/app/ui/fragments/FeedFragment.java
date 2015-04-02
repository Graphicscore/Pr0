package com.pr0gramm.app.ui.fragments;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.graphics.Point;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.view.MenuItemCompat;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.SearchView;
import android.util.Log;
import android.view.*;
import android.widget.ImageView;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.pr0gramm.app.AndroidUtility;
import com.pr0gramm.app.R;
import com.pr0gramm.app.Settings;
import com.pr0gramm.app.feed.*;
import com.pr0gramm.app.services.BookmarkService;
import com.pr0gramm.app.services.SeenService;
import com.pr0gramm.app.ui.FeedFilterFormatter;
import com.pr0gramm.app.ui.MainActionHandler;
import com.pr0gramm.app.ui.dialogs.ErrorDialogFragment;
import com.pr0gramm.app.ui.views.CustomSwipeRefreshLayout;
import com.squareup.picasso.Picasso;
import roboguice.fragment.RoboFragment;
import roboguice.inject.InjectView;
import rx.functions.Actions;

import javax.inject.Inject;
import java.util.List;

import static com.google.common.base.Objects.equal;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.pr0gramm.app.ui.ScrollHideToolbarListener.ToolbarActivity;
import static java.lang.Math.max;
import static rx.android.observables.AndroidObservable.bindFragment;

/**
 */
public class FeedFragment extends RoboFragment {
    private static final String ARG_FEED_FILTER = "FeedFragment.filter";
    private static final String ARG_FEED_START = "FeedFragment.start";

    @Inject
    private FeedService feedService;

    @Inject
    private Picasso picasso;

    @Inject
    private SharedPreferences sharedPreferences;

    @Inject
    private SeenService seenService;

    @Inject
    private Settings settings;

    @InjectView(R.id.list)
    private RecyclerView recyclerView;

    @InjectView(R.id.progress)
    private View progressView;

    @InjectView(R.id.refresh)
    private CustomSwipeRefreshLayout swipeRefreshLayout;

    private FeedAdapter adapter;
    private GridLayoutManager layoutManager;
    private IndicatorStyle seenIndicatorStyle;

    @Inject
    private BookmarkService bookmarkService;

    private boolean bookmarkable;
    private Optional<Long> autoOpenOnLoad = Optional.absent();
    private Optional<Long> autoScrollOnLoad = Optional.absent();


    /**
     * Initialize a new feed fragment.
     */
    public FeedFragment() {
        setHasOptionsMenu(true);
        setRetainInstance(true);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // initialize auto opening
        long startId = getArguments().getLong(ARG_FEED_START);
        autoOpenOnLoad = autoScrollOnLoad = Optional.fromNullable(startId > 0 ? startId : null);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        return inflater.inflate(R.layout.fragment_feed, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        if (adapter == null) {
            // create a new adapter if necessary
            adapter = newFeedAdapter();
            progressView.setVisibility(View.VISIBLE);
        }

        seenIndicatorStyle = settings.seenIndicatorStyle();

        // prepare the list of items
        int count = getThumbnailColumns();
        layoutManager = new GridLayoutManager(getActivity(), count);
        recyclerView.setLayoutManager(layoutManager);
        recyclerView.setAdapter(adapter);

        // we can still swipe up if we are not at the start of the feed.
        swipeRefreshLayout.setCanSwipeUpPredicate(() -> !adapter.getFeedProxy().isAtStart());

        swipeRefreshLayout.setOnRefreshListener(() -> {
            FeedProxy proxy = adapter.getFeedProxy();
            if (proxy.isAtStart() && !proxy.isLoading()) {
                proxy.restart(Optional.<Long>absent());
            } else {
                // do not refresh
                swipeRefreshLayout.setRefreshing(false);
            }
        });

        // use height of the toolbar to configure swipe refresh layout.
        int abHeight = AndroidUtility.getActionBarSize(getActivity());
        swipeRefreshLayout.setProgressViewOffset(false, 0, (int) (1.5 * abHeight));
        swipeRefreshLayout.setColorSchemeResources(R.color.primary);

        if (getActivity() instanceof ToolbarActivity) {
            ToolbarActivity activity = (ToolbarActivity) getActivity();
            activity.getScrollHideToolbarListener().reset();
        }

        // always update the title
        String title = FeedFilterFormatter.format(getActivity(), getCurrentFilter());
        getActivity().setTitle(title);

        setupInfiniteScroll();
    }

    private void onBookmarkableStateChanged(boolean bookmarkable) {
        this.bookmarkable = bookmarkable;
        getActivity().supportInvalidateOptionsMenu();
    }

    private FeedAdapter newFeedAdapter() {
        Log.i("Feed", "Restore adapter now");
        FeedFilter feedFilter = getArguments()
                .<FeedFilter>getParcelable(ARG_FEED_FILTER)
                .withContentType(settings.getContentType());

        long startAround = getArguments().getLong(ARG_FEED_START, -1);
        Optional<Long> around = Optional.fromNullable(startAround > 0 ? startAround : null);
        return new FeedAdapter(feedFilter, around);
    }

    @Override
    public void onResume() {
        super.onResume();

        // check if we should show the pin button or not.
        if (settings.showPinButton()) {
            bindFragment(this, bookmarkService.isBookmarkable(getCurrentFilter()))
                    .subscribe(this::onBookmarkableStateChanged, Actions.empty());
        }

        // check if content type has changed, and reload if necessary
        FeedFilter feedFilter = adapter.getFilter();
        boolean changed = !equal(feedFilter.getContentTypes(), settings.getContentType());
        if (changed) {
            Optional<Long> around = findFirstVisibleSfwItem().transform(FeedItem::getId);

            // set a new adapter if we have a new content type
            FeedFilter filter = feedFilter.withContentType(settings.getContentType());
            adapter = new FeedAdapter(filter, autoScrollOnLoad = around);
            recyclerView.setAdapter(adapter);
        }

        // set new indicator style
        if (seenIndicatorStyle != settings.seenIndicatorStyle()) {
            seenIndicatorStyle = settings.seenIndicatorStyle();
            adapter.notifyDataSetChanged();
        }
    }

    private Optional<FeedItem> findFirstVisibleSfwItem() {
        Optional<FeedItem> sfwItem = Optional.absent();

        List<FeedItem> items = adapter.getFeedProxy().getItems();

        int idx = layoutManager.findFirstVisibleItemPosition();
        if (idx != RecyclerView.NO_POSITION && idx < items.size()) {
            for (FeedItem item : items.subList(idx, items.size() - 1)) {
                if (item.isContentType(ContentType.SFW)) {
                    sfwItem = Optional.of(item);
                    break;
                }
            }
        }

        return sfwItem;
    }

    /**
     * Loads the next page when we are near the end of one page.
     */
    private void setupInfiniteScroll() {
        recyclerView.setOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                if (getActivity() instanceof ToolbarActivity) {
                    ToolbarActivity activity = (ToolbarActivity) getActivity();
                    activity.getScrollHideToolbarListener().onScrolled(dy);
                }

                int totalItemCount = layoutManager.getItemCount();
                FeedProxy proxy = adapter.getFeedProxy();
                if (proxy.isLoading())
                    return;

                if (dy > 0 && !proxy.isAtEnd()) {
                    int lastVisibleItem = layoutManager.findLastVisibleItemPosition();
                    if (totalItemCount > 12 && lastVisibleItem >= totalItemCount - 12) {
                        Log.i("FeedScroll", "Request next page now");
                        proxy.loadNextPage();
                    }
                }

                if (dy < 0 && !proxy.isAtStart()) {
                    int firstVisibleItem = layoutManager.findFirstVisibleItemPosition();
                    if (totalItemCount > 12 && firstVisibleItem < 12) {
                        Log.i("FeedScroll", "Request previous page now");
                        proxy.loadPreviousPage();
                    }
                }
            }
        });
    }

    /**
     * Depending on whether the screen is landscape or portrait,
     * we show a different number of items per row.
     */
    private int getThumbnailColumns() {
        checkNotNull(getActivity(), "must be attached to call this method");

        Display display = getActivity().getWindowManager().getDefaultDisplay();

        Point point = new Point();
        display.getSize(point);

        return point.x > point.y ? 5 : 3;
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.menu_feed, menu);

        MenuItem item = menu.findItem(R.id.action_search);
        initializeSearchView(item);
    }

    @Override
    public void onPrepareOptionsMenu(Menu menu) {
        menu.findItem(R.id.action_pin).setVisible(bookmarkable);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_pin) {
            pinCurrentFeedFilter();
            return true;
        }

        if (item.getItemId() == R.id.action_post_refresh) {
            // refresh feed
            restartFeed();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void restartFeed() {
        FeedProxy feedProxy = adapter.getFeedProxy();
        feedProxy.restart(Optional.<Long>absent());
    }

    private void pinCurrentFeedFilter() {
        // not bookmarkable anymore.
        onBookmarkableStateChanged(false);

        FeedFilter filter = getCurrentFilter();
        String title = FeedFilterFormatter.format(getActivity(), filter);
        ((MainActionHandler) getActivity()).pinFeedFilter(filter, title);
    }


    /**
     * Registers the listeners for the search view.
     *
     * @param item The item containing the search view.
     */
    private void initializeSearchView(MenuItem item) {
        SearchView searchView = (SearchView) MenuItemCompat.getActionView(item);

        searchView.setOnSearchClickListener(v -> {
            FeedFilter currentFilter = getCurrentFilter();
            searchView.setQuery(currentFilter.getTags().or(""), false);
        });

        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String term) {
                performSearch(term);
                return true;
            }

            @Override
            public boolean onQueryTextChange(String term) {
                return false;
            }
        });
    }

    private void performSearch(String term) {
        FeedFilter current = getCurrentFilter();
        FeedFilter filter = current.withTags(term);

        // do nothing, if the filter did not change
        if (equal(current, filter))
            return;

        ((MainActionHandler) getActivity()).onFeedFilterSelected(filter);
    }

    private void onItemClicked(int idx) {
        try {
            ((MainActionHandler) getActivity()).onPostClicked(adapter.getFeedProxy(), idx);
        } catch (IllegalStateException error) {
            Log.w("FeedFragment", "Error while showing post", error);
        }
    }

    /**
     * Creates a new {@link FeedFragment} for the given
     * feed type.
     *
     * @param feedFilter A query to use for getting data
     * @return The type new fragment that can be shown now.
     */
    public static FeedFragment newInstance(FeedFilter feedFilter, Optional<Long> start) {
        Bundle arguments = new Bundle();
        arguments.putParcelable(ARG_FEED_FILTER, feedFilter);
        if (start.isPresent()) {
            arguments.putLong(ARG_FEED_START, start.get());
        }

        FeedFragment fragment = new FeedFragment();
        fragment.setArguments(arguments);
        return fragment;
    }

    /**
     * Gets the current filter from this feed.
     *
     * @return The filter this feed uses.
     */
    public FeedFilter getCurrentFilter() {
        if (adapter == null)
            return new FeedFilter();

        return adapter.getFilter();
    }

    private class FeedAdapter extends RecyclerView.Adapter<FeedItemViewHolder> implements FeedProxy.OnChangeListener {
        private final FeedProxy feedProxy;

        public FeedAdapter(FeedFilter filter, Optional<Long> around) {
            this(new FeedProxy(filter), around);
        }

        public FeedAdapter(FeedProxy feedProxy, Optional<Long> around) {
            this.feedProxy = feedProxy;

            this.feedProxy.setOnChangeListener(this);
            this.feedProxy.setLoader(new FeedProxy.FragmentFeedLoader(FeedFragment.this, feedService) {
                @Override
                public void onLoadFinished() {
                    progressView.setVisibility(View.GONE);
                    swipeRefreshLayout.setRefreshing(false);

                    performAutoOpen();
                }

                @Override
                public void onError(Throwable error) {
                    if (getFeedProxy().getItemCount() == 0) {
                        ErrorDialogFragment.showErrorString(
                                getFragmentManager(),
                                getString(R.string.could_not_load_feed));
                    }
                }
            });

            // start the feed
            this.feedProxy.restart(around);
        }

        public FeedFilter getFilter() {
            return feedProxy.getFeedFilter();
        }

        public FeedProxy getFeedProxy() {
            return feedProxy;
        }

        @SuppressLint("InflateParams")
        @Override
        public FeedItemViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(getActivity());
            View view = inflater.inflate(R.layout.feed_item_view, null);
            return new FeedItemViewHolder(view);
        }

        @Override
        public void onBindViewHolder(FeedItemViewHolder view, int position) {
            FeedItem item = feedProxy.getItemAt(position);

            picasso.load("http://thumb.pr0gramm.com/" + item.getThumb())
                    .into(view.image);

            view.itemView.setOnClickListener(v -> onItemClicked(position));

            int row = position / layoutManager.getSpanCount();
            view.itemView.setPadding(0, row == 0 ? AndroidUtility.getActionBarSize(getActivity()) : 0, 0, 0);

            // check if this item was already seen.
            if (seenIndicatorStyle == IndicatorStyle.ICON) {
                view.seen.setVisibility(seenService.isSeen(item) ? View.VISIBLE : View.GONE);
            } else {
                view.seen.setVisibility(View.GONE);
            }
        }

        @Override
        public int getItemCount() {
            return feedProxy.getItemCount();
        }

        @Override
        public long getItemId(int position) {
            return feedProxy.getItemAt(position).getId();
        }

        @Override
        public void onItemRangeInserted(int start, int count) {
            notifyItemRangeInserted(start, count);
        }

        @Override
        public void onItemRangeRemoved(int start, int count) {
            notifyItemRangeRemoved(start, count);
        }
    }

    private void performAutoOpen() {
        if (autoScrollOnLoad.isPresent()) {
            int idx = findItemIndexById(autoScrollOnLoad.get());
            if (idx >= 0) {
                // over scroll a bit
                int scrollTo = max(idx - 3, 0);
                recyclerView.scrollToPosition(scrollTo);
            }
        }

        if (autoOpenOnLoad.isPresent()) {
            int idx = findItemIndexById(autoOpenOnLoad.get());
            if (idx > 0) {
                onItemClicked(idx);
            }
        }

        autoOpenOnLoad = Optional.absent();
        autoScrollOnLoad = Optional.absent();
    }

    private int findItemIndexById(long id) {
        // look for the index of the item with the given id
        return FluentIterable
                .from(adapter.getFeedProxy().getItems())
                .firstMatch(item -> item.getId() == id)
                .transform(item -> adapter.getFeedProxy().getPosition(item).or(-1))
                .or(-1);
    }

    private static class FeedItemViewHolder extends RecyclerView.ViewHolder {
        private final ImageView image;
        private final ImageView seen;

        public FeedItemViewHolder(View itemView) {
            super(itemView);

            image = (ImageView) checkNotNull(itemView.findViewById(R.id.image));
            seen = (ImageView) checkNotNull(itemView.findViewById(R.id.seen));
        }
    }
}
