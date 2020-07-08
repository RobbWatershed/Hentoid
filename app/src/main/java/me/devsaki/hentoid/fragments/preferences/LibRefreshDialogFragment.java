package me.devsaki.hentoid.fragments.preferences;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.Group;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.snackbar.BaseTransientBottomBar;
import com.google.android.material.snackbar.Snackbar;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.schedulers.Schedulers;
import me.devsaki.hentoid.R;
import me.devsaki.hentoid.events.ProcessEvent;
import me.devsaki.hentoid.util.FileHelper;
import me.devsaki.hentoid.util.ImportHelper;
import me.devsaki.hentoid.util.Preferences;
import timber.log.Timber;

import static androidx.core.view.ViewCompat.requireViewById;

/**
 * Created by Robb on 11/2018
 * Launcher dialog for the following features :
 * - Set download folder
 * - Library refresh
 */
public class LibRefreshDialogFragment extends DialogFragment {

    private static final String SHOW_OPTIONS = "show_options";
    private static final String CHOOSE_FOLDER = "choose_folder";
    private static final String EXTERNAL_LIBRARY = "external_library";

    private boolean showOptions;
    private boolean chooseFolder;
    private boolean externalLibrary;

    private ViewGroup rootView;
    private TextView step1Txt;
    private TextView step1FolderButton;
    private TextView step2Txt;
    private ProgressBar step2progress;
    private View step2check;
    private View step3block;
    private TextView step3Txt;
    private ProgressBar step3progress;
    private View step3check;
    private View step4block;
    private ProgressBar step4progress;
    private View step4check;
    private Group optionsGroup;

    // Disposable for RxJava
    protected final CompositeDisposable compositeDisposable = new CompositeDisposable();


    public static void invoke(@NonNull final FragmentManager fragmentManager, boolean showOptions, boolean chooseFolder, boolean externalLibrary) {
        LibRefreshDialogFragment fragment = new LibRefreshDialogFragment();

        Bundle args = new Bundle();
        args.putBoolean(SHOW_OPTIONS, showOptions);
        args.putBoolean(CHOOSE_FOLDER, chooseFolder);
        args.putBoolean(EXTERNAL_LIBRARY, externalLibrary);
        fragment.setArguments(args);

        fragment.show(fragmentManager, null);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (null == getArguments()) throw new IllegalArgumentException("No arguments found");
        showOptions = getArguments().getBoolean(SHOW_OPTIONS, false);
        chooseFolder = getArguments().getBoolean(CHOOSE_FOLDER, false);
        externalLibrary = getArguments().getBoolean(EXTERNAL_LIBRARY, false);

        EventBus.getDefault().register(this);
    }

    @Override
    public void onDestroyView() {
        EventBus.getDefault().unregister(this);
        compositeDisposable.clear();
        super.onDestroyView();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedState) {
        return inflater.inflate(R.layout.dialog_prefs_refresh, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View rootView, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(rootView, savedInstanceState);

        if (rootView instanceof ViewGroup) this.rootView = (ViewGroup) rootView;

        if (showOptions) { // Show option screen first
            CheckBox renameChk = requireViewById(rootView, R.id.refresh_options_rename);
            CheckBox cleanAbsentChk = requireViewById(rootView, R.id.refresh_options_remove_1);
            CheckBox cleanNoImagesChk = requireViewById(rootView, R.id.refresh_options_remove_2);
            CheckBox cleanUnreadableChk = requireViewById(rootView, R.id.refresh_options_remove_3);
            RadioButton externalChk = requireViewById(rootView, R.id.refresh_location_external);

            optionsGroup = requireViewById(rootView, R.id.refresh_options_group);

            RadioGroup locationGrp = requireViewById(rootView, R.id.refresh_location);
            locationGrp.setOnCheckedChangeListener((g, i) -> onLocationChanged(i));

            View okBtn = requireViewById(rootView, R.id.refresh_ok);
            okBtn.setOnClickListener(v -> launchRefreshImport(externalChk.isChecked(), renameChk.isChecked(), cleanAbsentChk.isChecked(), cleanNoImagesChk.isChecked(), cleanUnreadableChk.isChecked()));
        } else { // Show import progress layout immediately
            showImportProgressLayout(chooseFolder, externalLibrary);
        }
    }

    private void onLocationChanged(@IdRes int checkedId) {
        if (checkedId == R.id.refresh_location_external)
            optionsGroup.setVisibility(View.GONE);
        else optionsGroup.setVisibility(View.VISIBLE);
    }

    private void launchRefreshImport(boolean isExternal, boolean rename, boolean cleanAbsent, boolean cleanNoImages, boolean cleanUnreadable) {
        showImportProgressLayout(false, isExternal);
        setCancelable(false);

        // Run import
        if (isExternal) {
            Uri externalUri = Uri.parse(Preferences.getExternalLibraryUri());
            compositeDisposable.add(Single.fromCallable(() -> ImportHelper.setAndScanExternalFolder(requireContext(), externalUri))
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(
                            res -> {
                                if (ImportHelper.Result.INVALID_FOLDER == res || ImportHelper.Result.CREATE_FAIL == res)
                                    dismiss();
                            },
                            Timber::w
                    )
            );
        } else {
            ImportHelper.ImportOptions options = new ImportHelper.ImportOptions();
            options.rename = rename;
            options.cleanAbsent = cleanAbsent;
            options.cleanNoImages = cleanNoImages;
            options.cleanUnreadable = cleanUnreadable;

            Uri rootUri = Uri.parse(Preferences.getStorageUri());
            compositeDisposable.add(Single.fromCallable(() -> ImportHelper.setAndScanHentoidFolder(requireContext(), rootUri, false, options))
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(
                            res -> {
                                if (ImportHelper.Result.INVALID_FOLDER == res || ImportHelper.Result.CREATE_FAIL == res)
                                    dismiss();
                            },
                            Timber::w
                    )
            );
        }
    }

    private void showImportProgressLayout(boolean askFolder, boolean isExternal) {
        // Replace launch options layout with import progress layout
        rootView.removeAllViews();
        LayoutInflater.from(getActivity()).inflate(R.layout.include_import_steps, rootView, true);

        // Memorize UI elements that will be updated during the import events
        step1Txt = rootView.findViewById(R.id.import_step1_text);
        step1FolderButton = rootView.findViewById(R.id.import_step1_button);
        step2Txt = rootView.findViewById(R.id.import_step2_text);
        step2progress = rootView.findViewById(R.id.import_step2_bar);
        step2check = rootView.findViewById(R.id.import_step2_check);
        step3block = rootView.findViewById(R.id.import_step3);
        step3progress = rootView.findViewById(R.id.import_step3_bar);
        step3Txt = rootView.findViewById(R.id.import_step3_text);
        step3check = rootView.findViewById(R.id.import_step3_check);
        step4block = rootView.findViewById(R.id.import_step4);
        step4progress = rootView.findViewById(R.id.import_step4_bar);
        step4check = rootView.findViewById(R.id.import_step4_check);

        if (isExternal) {
            step1FolderButton.setText(R.string.api29_migration_step1_select_external);
            step1Txt.setText(R.string.api29_migration_step1_external);
        } else {
            step1FolderButton.setText(R.string.api29_migration_step1_select);
            step1Txt.setText(R.string.api29_migration_step1);
        }

        if (askFolder) {
            step1FolderButton.setVisibility(View.VISIBLE);
            step1FolderButton.setOnClickListener(v -> pickFolder(isExternal));
            pickFolder(isExternal); // Ask right away, there's no reason why the user should click again
        } else {
            ((TextView) rootView.findViewById(R.id.import_step1_folder)).setText(FileHelper.getFullPathFromTreeUri(requireContext(), Uri.parse(Preferences.getStorageUri()), true));
            rootView.findViewById(R.id.import_step1_check).setVisibility(View.VISIBLE);
            rootView.findViewById(R.id.import_step2).setVisibility(View.VISIBLE);
            step2progress.setIndeterminate(true);
        }
    }

    private void pickFolder(boolean isExternal) {
        ImportHelper.openFolderPicker(this, isExternal);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        compositeDisposable.add(Single.fromCallable(() -> ImportHelper.processPickerResult(requireActivity(), requestCode, resultCode, data))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        this::processPickerResult,
                        Timber::w
                )
        );
    }

    public void processPickerResult(@ImportHelper.Result int result) {
        switch (result) {
            case ImportHelper.Result.OK_EMPTY_FOLDER:
                dismiss();
                break;
            case ImportHelper.Result.OK_LIBRARY_DETECTED:
                // Hentoid folder is finally selected at this point -> Update UI
                updateOnSelectFolder();
                // Import service is already launched by the Helper; nothing else to do
                break;
            case ImportHelper.Result.OK_LIBRARY_DETECTED_ASK:
                updateOnSelectFolder();
                ImportHelper.showExistingLibraryDialog(requireContext(), this::onCancelExistingLibraryDialog);
                break;
            case ImportHelper.Result.CANCELED:
                Snackbar.make(rootView, R.string.import_canceled, BaseTransientBottomBar.LENGTH_LONG).show();
                break;
            case ImportHelper.Result.INVALID_FOLDER:
                Snackbar.make(rootView, R.string.import_invalid, BaseTransientBottomBar.LENGTH_LONG).show();
                setCancelable(true);
                break;
            case ImportHelper.Result.CREATE_FAIL:
                Snackbar.make(rootView, R.string.import_create_fail, BaseTransientBottomBar.LENGTH_LONG).show();
                setCancelable(true);
                break;
            case ImportHelper.Result.OTHER:
                Snackbar.make(rootView, R.string.import_other, BaseTransientBottomBar.LENGTH_LONG).show();
                setCancelable(true);
                break;
            default:
                // Nothing should happen here
        }
    }

    private void onCancelExistingLibraryDialog() {
        // Revert back to initial state where only the "Select folder" button is visible
        rootView.findViewById(R.id.import_step1_check).setVisibility(View.INVISIBLE);
        rootView.findViewById(R.id.import_step2).setVisibility(View.INVISIBLE);
        ((TextView) rootView.findViewById(R.id.import_step1_folder)).setText("");
        step1FolderButton.setVisibility(View.VISIBLE);
        setCancelable(true);
    }

    private void updateOnSelectFolder() {
        ((TextView) rootView.findViewById(R.id.import_step1_folder)).setText(FileHelper.getFullPathFromTreeUri(requireContext(), Uri.parse(Preferences.getStorageUri()), true));
        step1FolderButton.setVisibility(View.INVISIBLE);
        rootView.findViewById(R.id.import_step1_check).setVisibility(View.VISIBLE);
        rootView.findViewById(R.id.import_step2).setVisibility(View.VISIBLE);
        step2progress.setIndeterminate(true);
        setCancelable(false);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onImportEvent(ProcessEvent event) {
        ProgressBar progressBar;
        switch (event.step) {
            case (2):
                progressBar = step2progress;
                break;
            case (3):
                progressBar = step3progress;
                break;
            default:
                progressBar = step4progress;
                break;
        }
        if (ProcessEvent.EventType.PROGRESS == event.eventType) {
            if (event.elementsTotal > -1) {
                progressBar.setIndeterminate(false);
                progressBar.setMax(event.elementsTotal);
                progressBar.setProgress(event.elementsOK + event.elementsKO);
            } else {
                step2Txt.setText(event.elementName);
                progressBar.setIndeterminate(true);
            }
            if (3 == event.step) {
                step2progress.setIndeterminate(false);
                step2progress.setMax(1);
                step2progress.setProgress(1);
                step2Txt.setVisibility(View.GONE);
                step2check.setVisibility(View.VISIBLE);
                step3block.setVisibility(View.VISIBLE);
                step3Txt.setText(getResources().getString(R.string.api29_migration_step3, event.elementsKO + event.elementsOK, event.elementsTotal));
            } else if (4 == event.step) {
                step3check.setVisibility(View.VISIBLE);
                step4block.setVisibility(View.VISIBLE);
            }
        } else if (ProcessEvent.EventType.COMPLETE == event.eventType) {
            if (2 == event.step) {
                step2progress.setIndeterminate(false);
                step2progress.setMax(1);
                step2progress.setProgress(1);
                step2Txt.setVisibility(View.GONE);
                step2check.setVisibility(View.VISIBLE);
                step3block.setVisibility(View.VISIBLE);
            } else if (3 == event.step) {
                step3Txt.setText(getResources().getString(R.string.api29_migration_step3, event.elementsTotal, event.elementsTotal));
                step3check.setVisibility(View.VISIBLE);
                step4block.setVisibility(View.VISIBLE);
            } else if (4 == event.step) {
                step4check.setVisibility(View.VISIBLE);
                dismiss();
            }
        }
    }
}