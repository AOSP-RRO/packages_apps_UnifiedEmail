/*
 * Copyright (C) 2012 Google Inc.
 * Licensed to The Android Open Source Project.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.mail.ui;

import com.google.common.collect.Maps;

import android.app.Activity;
import android.app.Fragment;
import android.app.LoaderManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.CursorLoader;
import android.content.Intent;
import android.content.Loader;
import android.database.Cursor;
import android.database.CursorWrapper;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcelable;
import android.provider.Browser;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ResourceCursorAdapter;

import com.android.mail.FormattedDateBuilder;
import com.android.mail.R;
import com.android.mail.browse.ConversationContainer;
import com.android.mail.browse.ConversationViewHeader;
import com.android.mail.browse.ConversationWebView;
import com.android.mail.browse.MessageHeaderView;
import com.android.mail.browse.MessageHeaderView.MessageHeaderViewCallbacks;
import com.android.mail.providers.Account;
import com.android.mail.providers.Conversation;
import com.android.mail.providers.Folder;
import com.android.mail.providers.ListParams;
import com.android.mail.providers.Message;
import com.android.mail.providers.UIProvider;
import com.android.mail.providers.UIProvider.AccountCapabilities;
import com.android.mail.providers.UIProvider.FolderCapabilities;
import com.android.mail.utils.LogUtils;
import com.android.mail.utils.Utils;

import java.util.Map;

/**
 * The conversation view UI component.
 */
public final class ConversationViewFragment extends Fragment implements
        LoaderManager.LoaderCallbacks<Cursor>,
        ConversationViewHeader.ConversationViewHeaderCallbacks,
        MessageHeaderViewCallbacks {

    private static final String LOG_TAG = new LogUtils().getLogTag();

    private static final int MESSAGE_LOADER_ID = 0;

    private ControllableActivity mActivity;

    private Context mContext;

    private Conversation mConversation;

    private ConversationViewHeader mConversationHeader;

    private ConversationContainer mConversationContainer;

    private Account mAccount;

    private ConversationWebView mWebView;

    private HtmlConversationTemplates mTemplates;

    private String mBaseUri;

    private final Handler mHandler = new Handler();

    private final MailJsBridge mJsBridge = new MailJsBridge();

    private final WebViewClient mWebViewClient = new ConversationWebViewClient();

    private MessageListAdapter mAdapter;

    private boolean mViewsCreated;

    private MenuItem mChangeFoldersMenuItem;

    private float mDensity;

    private Folder mFolder;

    private static final String ARG_ACCOUNT = "account";
    private static final String ARG_CONVERSATION = "conversation";
    private static final String ARG_FOLDER = "folder";

    /**
     * Constructor needs to be public to handle orientation changes and activity lifecycle events.
     */
    public ConversationViewFragment() {
        super();
    }

    /**
     * Creates a new instance of {@link ConversationViewFragment}, initialized
     * to display conversation.
     */
    public static ConversationViewFragment newInstance(Account account,
            Conversation conversation, Folder folder) {
       ConversationViewFragment f = new ConversationViewFragment();
       Bundle args = new Bundle();
       args.putParcelable(ARG_ACCOUNT, account);
       args.putParcelable(ARG_CONVERSATION, conversation);
       args.putParcelable(ARG_FOLDER, folder);
       f.setArguments(args);
       return f;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        // Strictly speaking, we get back an android.app.Activity from getActivity. However, the
        // only activity creating a ConversationListContext is a MailActivity which is of type
        // ControllableActivity, so this cast should be safe. If this cast fails, some other
        // activity is creating ConversationListFragments. This activity must be of type
        // ControllableActivity.
        final Activity activity = getActivity();
        if (!(activity instanceof ControllableActivity)) {
            LogUtils.wtf(LOG_TAG, "ConversationViewFragment expects only a ControllableActivity to"
                    + "create it. Cannot proceed.");
        }
        mActivity = (ControllableActivity) activity;
        mContext = mActivity.getApplicationContext();
        if (mActivity.isFinishing()) {
            // Activity is finishing, just bail.
            return;
        }
        mActivity.attachConversationView(this);
        mTemplates = new HtmlConversationTemplates(mContext);

        mAdapter = new MessageListAdapter(mActivity.getActivityContext(),
                null /* cursor */, mAccount, getLoaderManager(), this);
        mConversationContainer.setOverlayAdapter(mAdapter);

        mDensity = getResources().getDisplayMetrics().density;

        // Show conversation and start loading messages.
        showConversation();
    }

    @Override
    public void onCreate(Bundle savedState) {
        LogUtils.v(LOG_TAG, "onCreate in FolderListFragment(this=%s)", this);
        super.onCreate(savedState);

        Bundle args = getArguments();
        mAccount = args.getParcelable(ARG_ACCOUNT);
        mConversation = args.getParcelable(ARG_CONVERSATION);
        mFolder = args.getParcelable(ARG_FOLDER);
        mBaseUri = "x-thread://" + mAccount.name + "/" + mConversation.id;

        // not really, we just want to get a crack to store a reference to the change_folders item
        setHasOptionsMenu(true);
    }

    @Override
    public View onCreateView(LayoutInflater inflater,
            ViewGroup container, Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.conversation_view, null);
        mConversationContainer = (ConversationContainer) rootView
                .findViewById(R.id.conversation_container);
        mWebView = (ConversationWebView) mConversationContainer.findViewById(R.id.webview);
        mConversationHeader = (ConversationViewHeader) mConversationContainer.findViewById(
                R.id.conversation_header);
        mConversationHeader.setCallbacks(this);

        mWebView.addJavascriptInterface(mJsBridge, "mail");
        mWebView.setWebViewClient(mWebViewClient);
        mWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                LogUtils.i(LOG_TAG, "JS: %s (%s:%d)", consoleMessage.message(),
                        consoleMessage.sourceId(), consoleMessage.lineNumber());
                return true;
            }
        });

        final WebSettings settings = mWebView.getSettings();

        settings.setJavaScriptEnabled(true);
        settings.setUseWideViewPort(true);

        settings.setLayoutAlgorithm(WebSettings.LayoutAlgorithm.NORMAL);

        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);

        mViewsCreated = true;

        return rootView;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mViewsCreated = false;
        mActivity.attachConversationView(null);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);

        mChangeFoldersMenuItem = menu.findItem(R.id.change_folders);
    }

    public void onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);
        boolean showMarkImportant = !mConversation.isImportant();
        Utils.setMenuItemVisibility(
                menu,
                R.id.mark_important,
                showMarkImportant
                        && mAccount
                                .supportsCapability(UIProvider.AccountCapabilities.MARK_IMPORTANT));
        Utils.setMenuItemVisibility(
                menu,
                R.id.mark_not_important,
                !showMarkImportant
                        && mAccount
                                .supportsCapability(UIProvider.AccountCapabilities.MARK_IMPORTANT));
        // TODO(mindyp) show/ hide spam and mute based on conversation
        // properties to be added.
        Utils.setMenuItemVisibility(menu, R.id.y_button,
                mAccount.supportsCapability(AccountCapabilities.ARCHIVE) && mFolder != null
                        && mFolder.supportsCapability(FolderCapabilities.ARCHIVE));
        Utils.setMenuItemVisibility(menu, R.id.report_spam,
                mAccount.supportsCapability(AccountCapabilities.REPORT_SPAM) && mFolder != null
                        && mFolder.supportsCapability(FolderCapabilities.REPORT_SPAM)
                        && !mConversation.spam);
        Utils.setMenuItemVisibility(
                menu,
                R.id.mute,
                mAccount.supportsCapability(AccountCapabilities.MUTE) && mFolder != null
                        && mFolder.supportsCapability(FolderCapabilities.DESTRUCTIVE_MUTE)
                        && !mConversation.muted);
    }
    /**
     * Handles a request to show a new conversation list, either from a search query or for viewing
     * a folder. This will initiate a data load, and hence must be called on the UI thread.
     */
    private void showConversation() {
        // initialize conversation header, measure its height manually, and inform template render
        // TODO: inform template render of initial header height
        mConversationHeader.setSubject(mConversation.subject, false /* notify */);
        if (mAccount.supportsCapability(
                UIProvider.AccountCapabilities.MULTIPLE_FOLDERS_PER_CONV)) {
            mConversationHeader.setFolders(mConversation, false /* notify */);
        }

        getLoaderManager().initLoader(MESSAGE_LOADER_ID, Bundle.EMPTY, this);
    }

    @Override
    public Loader<Cursor> onCreateLoader(int id, Bundle args) {
        return new MessageLoader(mContext, mConversation.messageListUri);
    }

    @Override
    public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
        MessageCursor messageCursor = (MessageCursor) data;

        if (mAdapter.getCursor() == null) {
            renderConversation(messageCursor);
        } else {
            updateConversation(messageCursor);
        }
    }

    @Override
    public void onLoaderReset(Loader<Cursor> loader) {
        mAdapter.swapCursor(null);
    }

    private void renderConversation(MessageCursor messageCursor) {
        mWebView.loadDataWithBaseURL(mBaseUri, renderMessageBodies(messageCursor), "text/html",
                "utf-8", null);
        mAdapter.swapCursor(messageCursor);
    }

    private void updateConversation(MessageCursor messageCursor) {
        // TODO: handle server-side conversation updates
        // for simple things like header data changes, just re-render the affected headers
        // if a new message is present, save off the pending cursor and show a notification to
        // re-render

        final MessageCursor oldCursor = (MessageCursor) mAdapter.getCursor();
        mAdapter.swapCursor(messageCursor);
    }

    private String renderMessageBodies(MessageCursor messageCursor) {
        int pos = -1;

        // N.B. the units of height for spacers are actually dp and not px because WebView assumes
        // a pixel is an mdpi pixel, unless you set device-dpi.

        final int headerHeightPx = Utils.measureViewHeight(mConversationHeader,
                mConversationContainer);
        mTemplates.startConversation((int) (headerHeightPx / mDensity));

        // FIXME: measure the header (and the attachments) and insert spacers of appropriate size
        final int spacerH = (Utils.useTabletUI(mContext)) ? 112 : 96;

        boolean allowNetworkImages = false;

        while (messageCursor.moveToPosition(++pos)) {
            final Message msg = messageCursor.get();
            // TODO: save/restore 'show pics' state
            final boolean safeForImages = msg.alwaysShowImages /* || savedStateSaysSafe */;
            allowNetworkImages |= safeForImages;
            mTemplates.appendMessageHtml(msg, true /* expanded */, safeForImages, 1.0f, spacerH);
        }

        mWebView.getSettings().setBlockNetworkImage(!allowNetworkImages);

        return mTemplates.endConversation(mBaseUri, 320);
    }

    public void onTouchEvent(MotionEvent event) {
        // TODO: (mindyp) when there is an undo bar, check for event !in undo bar
        // if its not in undo bar, dismiss the undo bar.
    }

    // BEGIN conversation header callbacks
    @Override
    public void onFoldersClicked() {
        if (mChangeFoldersMenuItem == null) {
            LogUtils.e(LOG_TAG, "unable to open 'change folders' dialog for a conversation");
            return;
        }
        mActivity.onOptionsItemSelected(mChangeFoldersMenuItem);
    }

    @Override
    public void onConversationViewHeaderHeightChange(int newHeight) {
        // TODO: propagate the new height to the header's HTML spacer. This can happen when labels
        // are added/removed
    }

    @Override
    public String getSubjectRemainder(String subject) {
        // TODO: hook this up to action bar
        return subject;
    }
    // END conversation header callbacks

    // START message header callbacks
    @Override
    public void setMessageSpacerHeight(Message msg, int height) {
        // TODO: update message HTML spacer height
        // TODO: expand this concept to handle bottom-aligned attachments
    }

    @Override
    public void setMessageExpanded(Message msg, boolean expanded, int spacerHeight) {
        // TODO: show/hide the HTML message body and update the spacer height
    }

    @Override
    public void showExternalResources(Message msg) {
        mWebView.getSettings().setBlockNetworkImage(false);
        mWebView.loadUrl("javascript:unblockImages('" + mTemplates.getMessageDomId(msg) + "');");
    }
    // END message header callbacks

    private static class MessageLoader extends CursorLoader {
        private boolean mDeliveredFirstResults = false;

        public MessageLoader(Context c, Uri uri) {
            super(c, uri, UIProvider.MESSAGE_PROJECTION, null, null, null);
        }

        @Override
        public Cursor loadInBackground() {
            return new MessageCursor(super.loadInBackground());

        }

        @Override
        public void deliverResult(Cursor result) {
            // We want to deliver these results, and then we want to make sure that any subsequent
            // queries do not hit the network
            super.deliverResult(result);

            if (!mDeliveredFirstResults) {
                mDeliveredFirstResults = true;
                Uri uri = getUri();

                // Create a ListParams that tells the provider to not hit the network
                final ListParams listParams =
                        new ListParams(ListParams.NO_LIMIT, false /* useNetwork */);

                // Build the new uri with this additional parameter
                uri = uri.buildUpon().appendQueryParameter(
                        UIProvider.LIST_PARAMS_QUERY_PARAMETER, listParams.serialize()).build();
                setUri(uri);
            }
        }
    }

    private static class MessageCursor extends CursorWrapper {

        private Map<Long, Message> mCache = Maps.newHashMap();

        public MessageCursor(Cursor inner) {
            super(inner);
        }

        public Message get() {
            final long id = getWrappedCursor().getLong(UIProvider.MESSAGE_ID_COLUMN);
            Message m = mCache.get(id);
            if (m == null) {
                m = new Message(this);
                mCache.put(id, m);
            }
            return m;
        }
    }

    private static class MessageListAdapter extends ResourceCursorAdapter {

        private final FormattedDateBuilder mDateBuilder;
        private final Account mAccount;
        private final LoaderManager mLoaderManager;
        private final MessageHeaderViewCallbacks mCallbacks;

        public MessageListAdapter(Context context, Cursor messageCursor, Account account,
                LoaderManager loaderManager, MessageHeaderViewCallbacks cb) {
            super(context, R.layout.conversation_message_header, messageCursor, 0);
            mDateBuilder = new FormattedDateBuilder(context);
            mAccount = account;
            mLoaderManager = loaderManager;
            mCallbacks = cb;
        }

        @Override
        public void bindView(View view, Context context, Cursor cursor) {
            final Message msg = ((MessageCursor) cursor).get();
            MessageHeaderView header = (MessageHeaderView) view;
            header.setCallbacks(mCallbacks);
            header.initialize(mDateBuilder, mAccount, mLoaderManager, true /* expanded */,
                    msg.shouldShowImagePrompt(), false /* defaultReplyAll */);
            header.bind(msg);
        }
    }

    private static int[] parseInts(final String[] stringArray) {
        final int len = stringArray.length;
        final int[] ints = new int[len];
        for (int i = 0; i < len; i++) {
            ints[i] = Integer.parseInt(stringArray[i]);
        }
        return ints;
    }

    private class ConversationWebViewClient extends WebViewClient {

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);

            // TODO: save off individual message unread state (here, or in onLoadFinished?) so
            // 'mark unread' restores the original unread state for each individual message

            // mark as read upon open
            if (!mConversation.read) {
                mConversation.markRead(mContext, true /* read */);
                mConversation.read = true;
            }
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            boolean result = false;
            final Uri uri = Uri.parse(url);
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            intent.putExtra(Browser.EXTRA_APPLICATION_ID, getActivity().getPackageName());
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);

            // FIXME: give provider a chance to customize url intents?
            // Utils.addGoogleUriAccountIntentExtras(mContext, uri, mAccount, intent);

            try {
                mActivity.getActivityContext().startActivity(intent);
                result = true;
            } catch (ActivityNotFoundException ex) {
                // If no application can handle the URL, assume that the
                // caller can handle it.
            }

            return result;
        }

    }

    /**
     * NOTE: all public methods must be listed in the proguard flags so that they can be accessed
     * via reflection and not stripped.
     *
     */
    private class MailJsBridge {

        @SuppressWarnings("unused")
        public void onWebContentGeometryChange(final String[] headerBottomStrs,
                final String[] headerHeightStrs) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (!mViewsCreated) {
                        LogUtils.d(LOG_TAG, "ignoring webContentGeometryChange because views" +
                                " are gone, %s", ConversationViewFragment.this);
                        return;
                    }

                    mConversationContainer.onGeometryChange(parseInts(headerBottomStrs),
                            parseInts(headerHeightStrs));
                }
            });
        }

    }

}
