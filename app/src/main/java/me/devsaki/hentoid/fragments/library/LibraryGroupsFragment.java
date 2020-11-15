package me.devsaki.hentoid.fragments.library;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.paging.PagedList;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.annimon.stream.Stream;
import com.google.android.material.snackbar.BaseTransientBottomBar;
import com.google.android.material.snackbar.Snackbar;
import com.mikepenz.fastadapter.FastAdapter;
import com.mikepenz.fastadapter.adapters.ItemAdapter;
import com.mikepenz.fastadapter.drag.ItemTouchCallback;
import com.mikepenz.fastadapter.drag.SimpleDragCallback;
import com.mikepenz.fastadapter.extensions.ExtensionsFactories;
import com.mikepenz.fastadapter.select.SelectExtension;
import com.mikepenz.fastadapter.select.SelectExtensionFactory;
import com.mikepenz.fastadapter.swipe.SimpleSwipeCallback;
import com.mikepenz.fastadapter.swipe_drag.SimpleSwipeDragCallback;
import com.mikepenz.fastadapter.utils.DragDropUtil;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.jetbrains.annotations.NotNull;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import me.devsaki.hentoid.BuildConfig;
import me.devsaki.hentoid.R;
import me.devsaki.hentoid.activities.LibraryActivity;
import me.devsaki.hentoid.database.domains.Content;
import me.devsaki.hentoid.database.domains.Group;
import me.devsaki.hentoid.enums.Site;
import me.devsaki.hentoid.enums.StatusContent;
import me.devsaki.hentoid.events.AppUpdatedEvent;
import me.devsaki.hentoid.events.CommunicationEvent;
import me.devsaki.hentoid.ui.InputDialog;
import me.devsaki.hentoid.util.ContentHelper;
import me.devsaki.hentoid.util.Debouncer;
import me.devsaki.hentoid.util.Preferences;
import me.devsaki.hentoid.util.ToastUtil;
import me.devsaki.hentoid.viewholders.GroupDisplayItem;
import me.devsaki.hentoid.viewholders.IDraggableViewHolder;
import me.devsaki.hentoid.viewmodels.LibraryViewModel;
import me.devsaki.hentoid.viewmodels.ViewModelFactory;
import me.devsaki.hentoid.widget.AutofitGridLayoutManager;
import me.zhanghai.android.fastscroll.FastScrollerBuilder;
import timber.log.Timber;

import static androidx.core.view.ViewCompat.requireViewById;
import static me.devsaki.hentoid.events.CommunicationEvent.EV_SEARCH;
import static me.devsaki.hentoid.events.CommunicationEvent.EV_UPDATE_SORT;
import static me.devsaki.hentoid.events.CommunicationEvent.RC_GROUPS;

@SuppressLint("NonConstantResourceId")
public class LibraryGroupsFragment extends Fragment implements ItemTouchCallback, SimpleSwipeCallback.ItemSwipeCallback {

    private static final String KEY_LAST_LIST_POSITION = "last_list_position";


    // ======== COMMUNICATION
    private OnBackPressedCallback callback;
    // Viewmodel
    private LibraryViewModel viewModel;
    // Settings listener
    private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener = (p, k) -> onSharedPreferenceChanged(k);
    // Activity
    private WeakReference<LibraryActivity> activity;


    // ======== UI
    // Text that displays in the background when the list is empty
    private TextView emptyText;
    // Main view where books are displayed
    private RecyclerView recyclerView;
    // LayoutManager of the recyclerView
    private LinearLayoutManager llm;

    // === SORT TOOLBAR
    // Sort direction button
    private ImageView sortDirectionButton;
    // Sort field button
    private TextView sortFieldButton;

    // === FASTADAPTER COMPONENTS AND HELPERS
    private ItemAdapter<GroupDisplayItem> itemAdapter;
    private FastAdapter<GroupDisplayItem> fastAdapter;
    private SelectExtension<GroupDisplayItem> selectExtension;
    private ItemTouchHelper touchHelper;


    // ======== VARIABLES
    // Records the system time (ms) when back button has been last pressed (to detect "double back button" event)
    private long backButtonPressed;
    // Used to ignore native calls to onBookClick right after that book has been deselected
    private boolean invalidateNextBookClick = false;
    // Total number of books in the whole unfiltered library
    private int totalContentCount;
    // Position of top item to memorize or restore (used when activity is destroyed and recreated)
    private int topItemPosition = -1;

    // Used to start processing when the recyclerView has finished updating
    private Debouncer<Integer> listRefreshDebouncer;
    private int itemToRefreshIndex = -1;


    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        callback = new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                customBackPress();
            }
        };

        if (!(requireActivity() instanceof LibraryActivity))
            throw new IllegalStateException("Parent activity has to be a LibraryActivity");
        activity = new WeakReference<>((LibraryActivity) requireActivity());
        activity.get().getOnBackPressedDispatcher().addCallback(activity.get(), callback);
        listRefreshDebouncer = new Debouncer<>(context, 75, this::onRecyclerUpdated);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ExtensionsFactories.INSTANCE.register(new SelectExtensionFactory());
        EventBus.getDefault().register(this);
        setRetainInstance(true);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.fragment_library_groups, container, false);

        Preferences.registerPrefsChangedListener(prefsListener);

        ViewModelFactory vmFactory = new ViewModelFactory(requireActivity().getApplication());
        viewModel = new ViewModelProvider(requireActivity(), vmFactory).get(LibraryViewModel.class);

        initUI(rootView);
        activity.get().initFragmentToolbars(selectExtension, this::toolbarOnItemClicked, this::selectionToolbarOnItemClicked);

        return rootView;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        viewModel.getGroups().observe(getViewLifecycleOwner(), this::onGroupsChanged);
        viewModel.getLibraryPaged().observe(getViewLifecycleOwner(), this::onLibraryChanged);

        viewModel.setGrouping(Preferences.getGroupingDisplay(), Preferences.getGroupSortField(), Preferences.isGroupSortDesc(), Preferences.getArtistGroupVisibility()); // Trigger a blank search
    }

    /**
     * Initialize the UI components
     *
     * @param rootView Root view of the library screen
     */
    private void initUI(@NonNull View rootView) {
        emptyText = requireViewById(rootView, R.id.library_empty_txt);
        sortDirectionButton = activity.get().getSortDirectionButton();
        sortFieldButton = activity.get().getSortFieldButton();

        // RecyclerView
        recyclerView = requireViewById(rootView, R.id.library_list);
        if (Preferences.Constant.LIBRARY_DISPLAY_LIST == Preferences.getLibraryDisplay())
            llm = new LinearLayoutManager(requireContext(), LinearLayoutManager.VERTICAL, false);
        else
            llm = new AutofitGridLayoutManager(requireContext(), (int) getResources().getDimension(R.dimen.card_grid_width));
        recyclerView.setLayoutManager(llm);
        new FastScrollerBuilder(recyclerView).build();

        // Pager
        setPagingMethod();

        updateSortControls();
    }

    private void updateSortControls() {
        // Sort controls
        sortDirectionButton.setImageResource(Preferences.isGroupSortDesc() ? R.drawable.ic_simple_arrow_down : R.drawable.ic_simple_arrow_up);
        sortDirectionButton.setOnClickListener(v -> {
            boolean sortDesc = !Preferences.isGroupSortDesc();
            Preferences.setGroupSortDesc(sortDesc);
            // Update icon
            sortDirectionButton.setImageResource(sortDesc ? R.drawable.ic_simple_arrow_down : R.drawable.ic_simple_arrow_up);
            // Run a new search
            viewModel.updateContentOrder();
            activity.get().sortCommandsAutoHide(true, null);
        });
        sortFieldButton.setText(getNameFromFieldCode(Preferences.getGroupSortField()));
        sortFieldButton.setOnClickListener(v -> {
            // Load and display the field popup menu
            PopupMenu popup = new PopupMenu(requireContext(), sortDirectionButton);
            popup.getMenuInflater()
                    .inflate(R.menu.library_groups_sort_popup, popup.getMenu());
            popup.getMenu().findItem(R.id.sort_custom).setVisible(Preferences.getGroupingDisplay().canReorderGroups());
            popup.setOnMenuItemClickListener(item -> {
                // Update button text
                sortFieldButton.setText(item.getTitle());
                item.setChecked(true);
                int fieldCode = getFieldCodeFromMenuId(item.getItemId());
                Preferences.setGroupSortField(fieldCode);
                // Run a new search
                viewModel.searchGroup(Preferences.getGroupingDisplay(), activity.get().getQuery(), fieldCode, Preferences.isGroupSortDesc(), Preferences.getArtistGroupVisibility());
                activity.get().sortCommandsAutoHide(true, popup);
                return true;
            });
            popup.show(); //showing popup menu
            activity.get().sortCommandsAutoHide(true, popup);
        }); //closing the setOnClickListener method
    }

    private int getFieldCodeFromMenuId(@IdRes int menuId) {
        switch (menuId) {
            case (R.id.sort_title):
                return Preferences.Constant.ORDER_FIELD_TITLE;
            case (R.id.sort_books):
                return Preferences.Constant.ORDER_FIELD_CHILDREN;
            case (R.id.sort_custom):
                return Preferences.Constant.ORDER_FIELD_CUSTOM;
            default:
                return Preferences.Constant.ORDER_FIELD_NONE;
        }
    }

    private int getNameFromFieldCode(int prefFieldCode) {
        switch (prefFieldCode) {
            case (Preferences.Constant.ORDER_FIELD_TITLE):
                return R.string.sort_title;
            case (Preferences.Constant.ORDER_FIELD_CHILDREN):
                return R.string.sort_books;
            case (Preferences.Constant.ORDER_FIELD_CUSTOM):
                return R.string.sort_custom;
            default:
                return R.string.sort_invalid;
        }
    }

    private boolean toolbarOnItemClicked(@NonNull MenuItem menuItem) {
        switch (menuItem.getItemId()) {
            case R.id.action_edit:
                toggleEditMode();
                break;
            case R.id.action_edit_cancel:
                cancelEditMode();
                break;
            case R.id.action_group_new:
                newGroupPrompt();
                break;
            default:
                return activity.get().toolbarOnItemClicked(menuItem);
        }
        return true;
    }

    private boolean selectionToolbarOnItemClicked(@NonNull MenuItem menuItem) {
        switch (menuItem.getItemId()) {
            case R.id.action_edit_name:
                editSelectedItemName();
                break;
            case R.id.action_delete:
                purgeSelectedItems();
                break;
            case R.id.action_archive:
                archiveSelectedItems();
                break;
            default:
                activity.get().getSelectionToolbar().setVisibility(View.GONE);
                return false;
        }
        activity.get().getSelectionToolbar().setVisibility(View.GONE);
        return true;
    }

    private void toggleEditMode() {
        activity.get().toggleEditMode();

        // Leave edit mode by validating => Save new item position
        if (!activity.get().isEditMode()) {
            // Set ordering field to custom
            Preferences.setGroupSortField(Preferences.Constant.ORDER_FIELD_CUSTOM);
            sortFieldButton.setText(getNameFromFieldCode(Preferences.Constant.ORDER_FIELD_CUSTOM));
            // Set ordering direction to ASC (we just manually ordered stuff; it has to be displayed as is)
            Preferences.setGroupSortDesc(false);
            viewModel.saveGroupPositions(Stream.of(itemAdapter.getAdapterItems()).map(GroupDisplayItem::getGroup).withoutNulls().toList());
        }

        setPagingMethod();
    }

    private void cancelEditMode() {
        activity.get().setEditMode(false);
        setPagingMethod();
    }

    private void newGroupPrompt() {
        InputDialog.invokeInputDialog(requireActivity(), R.string.new_group_name, groupName -> viewModel.newGroup(Preferences.getGroupingDisplay(), groupName, this::onNewGroupNameExists));
    }

    private void onNewGroupNameExists() {
        ToastUtil.toast(R.string.group_name_exists);
        newGroupPrompt();
    }

    /**
     * Callback for the "delete item" action button
     */
    private void purgeSelectedItems() {
        Set<GroupDisplayItem> selectedItems = selectExtension.getSelectedItems();
        if (!selectedItems.isEmpty()) {
            List<Group> selectedGroups = Stream.of(selectedItems).map(GroupDisplayItem::getGroup).withoutNulls().toList();
            List<Content> selectedContent = Stream.of(selectedGroups).map(Group::getContents).single();
            // Remove external items if they can't be deleted
            if (!Preferences.isDeleteExternalLibrary()) {
                List<Content> contentToDelete = Stream.of(selectedContent).filterNot(c -> c.getStatus().equals(StatusContent.EXTERNAL)).toList();
                int diff = selectedContent.size() - contentToDelete.size();
                // Remove undeletable books from the list
                if (diff > 0) {
                    Snackbar.make(recyclerView, getResources().getQuantityString(R.plurals.external_not_removed, diff, diff), BaseTransientBottomBar.LENGTH_LONG).show();
                    selectedContent = contentToDelete;
                    // Rebuild the groups list from the remaining contents if needed
                    if (Preferences.getGroupingDisplay().canReorderGroups())
                        selectedGroups = Stream.of(selectedContent).flatMap(c -> Stream.of(c.groupItems)).map(gi -> gi.group.getTarget()).toList();
                }
            }
            // Non-custom groups -> groups are removed automatically as soon as they don't contain any content => no need to remove the groups manually
            if (!Preferences.getGroupingDisplay().canReorderGroups()) selectedGroups.clear();

            if (!selectedContent.isEmpty() || !selectedGroups.isEmpty())
                activity.get().askDeleteItems(selectedContent, selectedGroups, null, selectExtension);
        }
    }

    /**
     * Callback for the "archive item" action button
     */
    private void archiveSelectedItems() {
        Set<GroupDisplayItem> selectedItems = selectExtension.getSelectedItems();
        List<Content> selectedContent = Stream.of(selectedItems)
                .map(GroupDisplayItem::getGroup)
                .withoutNulls()
                .flatMap(g -> Stream.of(g.getContents()))
                .withoutNulls()
                .filterNot(c -> c.getStorageUri().isEmpty())
                .toList();
        if (!selectedContent.isEmpty())
            activity.get().askArchiveItems(selectedContent, selectExtension);
    }

    /**
     * Callback for the "edit item name" action button
     */
    private void editSelectedItemName() {
        Set<GroupDisplayItem> selectedItems = selectExtension.getSelectedItems();
        Group g = Stream.of(selectedItems).map(GroupDisplayItem::getGroup).withoutNulls().findFirst().get();

        InputDialog.invokeInputDialog(requireActivity(), R.string.group_edit_name, g.name, this::onEditName);
    }

    private void onEditName(@NonNull final String newName) {
        Set<GroupDisplayItem> selectedItems = selectExtension.getSelectedItems();
        Group g = Stream.of(selectedItems).map(GroupDisplayItem::getGroup).withoutNulls().findFirst().get();
        viewModel.renameGroup(g, newName, () -> {
            ToastUtil.toast(R.string.group_name_exists);
            LibraryGroupsFragment.this.editSelectedItemName();
        });
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (viewModel != null) viewModel.onSaveState(outState);
        if (fastAdapter != null) fastAdapter.saveInstanceState(outState);

        // Remember current position in the sorted list
        int currentPosition = getTopItemPosition();
        if (currentPosition > 0 || -1 == topItemPosition) topItemPosition = currentPosition;

        outState.putInt(KEY_LAST_LIST_POSITION, topItemPosition);
        topItemPosition = -1;
    }

    @Override
    public void onViewStateRestored(@Nullable Bundle savedInstanceState) {
        super.onViewStateRestored(savedInstanceState);

        topItemPosition = 0;
        if (null == savedInstanceState) return;

        if (viewModel != null) viewModel.onRestoreState(savedInstanceState);
        if (fastAdapter != null) fastAdapter.withSavedInstanceState(savedInstanceState);
        // Mark last position in the list to be the one it will come back to
        topItemPosition = savedInstanceState.getInt(KEY_LAST_LIST_POSITION, 0);
    }

    @Subscribe(sticky = true, threadMode = ThreadMode.MAIN)
    public void onAppUpdated(AppUpdatedEvent event) {
        EventBus.getDefault().removeStickyEvent(event);
        // Display the "update success" dialog when an update is detected on a release version
        if (!BuildConfig.DEBUG) UpdateSuccessDialogFragment.invoke(getParentFragmentManager());
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onActivityEvent(CommunicationEvent event) {
        if (event.getRecipient() != RC_GROUPS) return;
        switch (event.getType()) {
            case EV_SEARCH:
                if (event.getMessage() != null) onSubmitSearch(event.getMessage());
                break;
            case EV_UPDATE_SORT:
                updateSortControls();
                activity.get().initFragmentToolbars(selectExtension, this::toolbarOnItemClicked, this::selectionToolbarOnItemClicked);
                break;
            default:
                // No default behaviour
        }
    }

    @Override
    public void onDestroy() {
        Preferences.unregisterPrefsChangedListener(prefsListener);
        EventBus.getDefault().unregister(this);
        if (callback != null) callback.remove();
        super.onDestroy();
    }

    private void customBackPress() {
        // If content is selected, deselect it
        if (!selectExtension.getSelectedItems().isEmpty()) {
            selectExtension.deselect();
            activity.get().getSelectionToolbar().setVisibility(View.GONE);
            backButtonPressed = 0;
            return;
        }

        if (!activity.get().collapseSearchMenu()) {
            // If none of the above and a search filter is on => clear search filter
            if (activity.get().isSearchQueryActive()) {
                activity.get().setQuery("");
                activity.get().setMetadata(Collections.emptyList());
                activity.get().hideSearchSortBar(false);
                viewModel.searchContent(activity.get().getQuery(), activity.get().getMetadata());
            }
            // If none of the above, user is asking to leave => use double-tap
            if (backButtonPressed + 2000 > SystemClock.elapsedRealtime()) {
                callback.remove();
                requireActivity().onBackPressed();
            } else {
                backButtonPressed = SystemClock.elapsedRealtime();
                ToastUtil.toast(R.string.press_back_again);

                llm.scrollToPositionWithOffset(0, 0);
            }
        }
    }

    /**
     * Callback for any change in Preferences
     */
    private void onSharedPreferenceChanged(String key) {
        Timber.i("Prefs change detected : %s", key);
        switch (key) {
            case Preferences.Key.GROUPING_DISPLAY:
            case Preferences.Key.ARTIST_GROUP_VISIBILITY:
                viewModel.setGrouping(Preferences.getGroupingDisplay(), Preferences.getGroupSortField(), Preferences.isGroupSortDesc(), Preferences.getArtistGroupVisibility());
                break;
            default:
                // Nothing to handle there
        }
    }

    /**
     * Initialize the paging method of the screen
     */
    private void setPagingMethod(/*boolean isEditMode*/) {
        viewModel.setPagingMethod(true);

        itemAdapter = new ItemAdapter<>();
        fastAdapter = FastAdapter.with(itemAdapter);
        fastAdapter.setHasStableIds(true);

        // Item click listener
        fastAdapter.setOnClickListener((v, a, i, p) -> onGroupClick(i, p));

        // Gets (or creates and attaches if not yet existing) the extension from the given `FastAdapter`
        selectExtension = fastAdapter.getOrCreateExtension(SelectExtension.class);
        if (selectExtension != null) {
            selectExtension.setSelectable(true);
            selectExtension.setMultiSelect(true);
            selectExtension.setSelectOnLongClick(true);
            selectExtension.setSelectionListener((i, b) -> this.onSelectionChanged());
        }

        // Drag, drop & swiping
        if (activity.get().isEditMode()) {
            SimpleDragCallback dragSwipeCallback = new SimpleSwipeDragCallback(
                    this,
                    this,
                    ContextCompat.getDrawable(requireContext(), R.drawable.ic_action_delete_forever)).withSensitivity(10f).withSurfaceThreshold(0.75f);
            dragSwipeCallback.setNotifyAllDrops(true);
            dragSwipeCallback.setIsDragEnabled(false); // Despite its name, that's actually to disable drag on long tap

            touchHelper = new ItemTouchHelper(dragSwipeCallback);
            touchHelper.attachToRecyclerView(recyclerView);
        }

        recyclerView.setAdapter(fastAdapter);
    }

    private void onGroupsChanged(List<Group> result) {
        Timber.i(">>Groups changed ! Size=%s", result.size());

        boolean isEmpty = (result.isEmpty());
        emptyText.setVisibility(isEmpty ? View.VISIBLE : View.GONE);

        final @GroupDisplayItem.ViewType int viewType =
                activity.get().isEditMode() ? GroupDisplayItem.ViewType.LIBRARY_EDIT :
                        (Preferences.Constant.LIBRARY_DISPLAY_LIST == Preferences.getLibraryDisplay()) ?
                                GroupDisplayItem.ViewType.LIBRARY :
                                GroupDisplayItem.ViewType.LIBRARY_GRID;

        List<GroupDisplayItem> groups = Stream.of(result).map(g -> new GroupDisplayItem(g, touchHelper, viewType)).toList();
        itemAdapter.set(groups);
        differEndCallback();
    }

    /**
     * LiveData callback when the library changes
     * Happens when a book has been downloaded or deleted
     *
     * @param result Current library according to active filters
     */
    private void onLibraryChanged(PagedList<Content> result) {
        Timber.i(">>Library changed (groups) ! Size=%s", result.size());

        // Refresh groups
        // TODO do we really want to do that, especially when deleting content ?
        viewModel.setGrouping(Preferences.getGroupingDisplay(), Preferences.getGroupSortField(), Preferences.isGroupSortDesc(), Preferences.getArtistGroupVisibility());
    }

    // TODO doc
    private void onSubmitSearch(@NonNull final String query) {
        if (query.startsWith("http")) { // Quick-open a page
            Site s = Site.searchByUrl(query);
            if (null == s)
                Snackbar.make(recyclerView, R.string.malformed_url, BaseTransientBottomBar.LENGTH_SHORT).show();
            else if (s.equals(Site.NONE))
                Snackbar.make(recyclerView, R.string.unsupported_site, BaseTransientBottomBar.LENGTH_SHORT).show();
            else
                ContentHelper.launchBrowserFor(requireContext(), s, query);
        } else {
            viewModel.searchGroup(Preferences.getGroupingDisplay(), query, Preferences.getGroupSortField(), Preferences.isGroupSortDesc(), Preferences.getArtistGroupVisibility());
        }
    }

    /**
     * Callback for the group holder itself
     *
     * @param item GroupDisplayItem that has been clicked on
     */
    private boolean onGroupClick(@NonNull GroupDisplayItem item, int position) {
        if (selectExtension.getSelectedItems().isEmpty()) {
            if (!invalidateNextBookClick && item.getGroup() != null && !item.getGroup().isBeingDeleted()) {
                topItemPosition = position;
                activity.get().showBooksInGroup(item.getGroup());
            } else invalidateNextBookClick = false;

            return true;
        } else {
            selectExtension.setSelectOnLongClick(false);
        }
        return false;
    }

    /**
     * Callback for any selection change (item added to or removed from selection)
     */
    private void onSelectionChanged() {
        Set<GroupDisplayItem> selectedItems = selectExtension.getSelectedItems();
        int selectedTotalCount = selectedItems.size();

        if (0 == selectedTotalCount) {
            activity.get().getSelectionToolbar().setVisibility(View.GONE);
            selectExtension.setSelectOnLongClick(true);
            invalidateNextBookClick = true;
            new Handler(Looper.getMainLooper()).postDelayed(() -> invalidateNextBookClick = false, 200);
        } else {
            long selectedLocalCount = Stream.of(selectedItems).map(GroupDisplayItem::getGroup).withoutNulls().count();
            activity.get().updateSelectionToolbar(selectedTotalCount, selectedLocalCount);
            activity.get().getSelectionToolbar().setVisibility(View.VISIBLE);
        }
    }

    /**
     * Callback for the end of item diff calculations
     * Activated when all _adapter_ items are placed on their definitive position
     */
    private void differEndCallback() {
        if (topItemPosition > -1) {
            int targetPos = topItemPosition;
            listRefreshDebouncer.submit(targetPos);
            topItemPosition = -1;
        }
    }

    /**
     * Callback for the end of recycler updates
     * Activated when all _displayed_ items are placed on their definitive position
     */
    private void onRecyclerUpdated(int topItemPosition) {
        int currentPosition = getTopItemPosition();
        if (currentPosition != topItemPosition)
            llm.scrollToPositionWithOffset(topItemPosition, 0); // Used to restore position after activity has been stopped and recreated
    }

    /**
     * Calculate the position of the top visible item of the book list
     *
     * @return position of the top visible item of the book list
     */
    private int getTopItemPosition() {
        return Math.max(llm.findFirstVisibleItemPosition(), llm.findFirstCompletelyVisibleItemPosition());
    }

    /**
     * DRAG, DROP & SWIPE METHODS
     */

    private void recordMoveFromFirstPos(int from, int to) {
        if (0 == from) itemToRefreshIndex = to;
    }

    private void recordMoveFromFirstPos(List<Integer> positions) {
        // Only useful when moving the 1st item to the bottom
        if (!positions.isEmpty() && 0 == positions.get(0))
            itemToRefreshIndex = itemAdapter.getAdapterItemCount() - positions.size();
    }

    @Override
    public boolean itemTouchOnMove(int oldPosition, int newPosition) {
        DragDropUtil.onMove(itemAdapter, oldPosition, newPosition); // change position
        recordMoveFromFirstPos(oldPosition, newPosition);
        return true;
    }

    @Override
    public void itemTouchDropped(int i, int i1) {
        // Nothing; final position will be saved once the "save" button is hit
    }

    @Override
    public void itemTouchStartDrag(RecyclerView.@NotNull ViewHolder viewHolder) {
        if (viewHolder instanceof IDraggableViewHolder) {
            ((IDraggableViewHolder) viewHolder).onDragged();
        }
    }

    @Override
    public void itemSwiped(int i, int i1) {
        // TODO
    }
}
