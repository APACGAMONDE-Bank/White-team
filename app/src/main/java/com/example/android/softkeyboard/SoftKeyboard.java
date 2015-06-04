/*
 * Copyright (C) 2008-2009 The Android Open Source Project
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

package com.example.android.softkeyboard;

import android.content.res.Resources;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.Context;
import android.inputmethodservice.InputMethodService;
import android.inputmethodservice.Keyboard;
import android.inputmethodservice.KeyboardView;
import android.text.InputType;
import android.text.method.MetaKeyKeyListener;
import android.util.Log;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;

/*import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.GeometryFactory;
import com.vividsolutions.jts.geom.LineString;
import com.vividsolutions.jts.geom.LinearRing;
import com.vividsolutions.jts.geom.MultiPoint;
import com.vividsolutions.jts.geom.Polygon;
import com.vividsolutions.jts.geom.PrecisionModel;
*/
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.BitSet;
import java.util.Set;

import static java.lang.Math.abs;
import static java.lang.Math.pow;
import static java.lang.StrictMath.sqrt;




/**
 * Example of writing an input method for a soft keyboard.  This code is
 * focused on simplicity over completeness, so it should in no way be considered
 * to be a complete soft keyboard implementation.  Its purpose is to provide
 * a basic example for how you would get started writing an input method, to
 * be fleshed out as appropriate.
 */
public class SoftKeyboard extends InputMethodService 
        implements KeyboardView.OnKeyboardActionListener {
    static final boolean DEBUG = false;
    
    /**
     * This boolean indicates the optional example code for performing
     * processing of hard keys in addition to regular text generation
     * from on-screen interaction.  It would be used for input methods that
     * perform language translations (such as converting text entered on 
     * a QWERTY keyboard to Chinese), but may not be used for input methods
     * that are primarily intended to be used for on-screen text entry.
     */
    static final boolean PROCESS_HARD_KEYS = true;

    private InputMethodManager mInputMethodManager;

    private LatinKeyboardView mInputView;
    private CandidateView mCandidateView;
    private CompletionInfo[] mCompletions;
    
    private StringBuilder mComposing = new StringBuilder();
    private boolean mPredictionOn;
    private boolean mCompletionOn;
    private int mLastDisplayWidth;
    private boolean mCapsLock;
    private long mLastShiftTime;
    private long mMetaState;
    
    private LatinKeyboard mSymbolsKeyboard;
    private LatinKeyboard mSymbolsShiftedKeyboard;
    private LatinKeyboard mQwertyKeyboard;
    private LatinKeyboard mEnglishKeyboard;
    private LatinKeyboard mRussianKeyboard;
    
    private LatinKeyboard mCurKeyboard;

    private String mWordSeparators;
    private String cppJsonString;
    private JSONObject cppJson;
    private Map<BitSet,List<String>> cppStringHash ;

        /**
         * Main initialization of the input method component.  Be sure to call
         * to super class.
         */
    @Override public void onCreate() {
        super.onCreate();
        mInputMethodManager = (InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
        mWordSeparators = getResources().getString(R.string.word_separators);


    }
    
    /**
     * This is the point where you can do all of your UI initialization.  It
     * is called after creation and any configuration change.
     */
    @Override public void onInitializeInterface() {

        mRussianKeyboard = new LatinKeyboard(this, R.xml.russian);

        mEnglishKeyboard = new LatinKeyboard(this, R.xml.qwerty);
        mSymbolsKeyboard = new LatinKeyboard(this, R.xml.symbols);
        mSymbolsShiftedKeyboard = new LatinKeyboard(this, R.xml.symbols_shift);
        mQwertyKeyboard = mEnglishKeyboard;
    }
    
    /**
     * Called by the framework when your view for creating input needs to
     * be generated.  This will be called the first time your input method
     * is displayed, and every time it needs to be re-created such as due to
     * a configuration change.
     */
    @Override public View onCreateInputView() {
        mInputView = (LatinKeyboardView) getLayoutInflater().inflate(
                R.layout.input, null);
        mInputView.setOnKeyboardActionListener(this);
        mInputView.setKeyboard(mQwertyKeyboard);




        return mInputView;
    }

    /**
     * Called by the framework when your view for showing candidates needs to
     * be generated, like {@link #onCreateInputView}.
     */
    @Override public View onCreateCandidatesView() {
        mCandidateView = new CandidateView(this);
        mCandidateView.setService(this);
        setCandidatesViewShown(true);
        return mCandidateView;
    }

    /**
     * This is the main point where we do our initialization of the input method
     * to begin operating on an application.  At this point we have been
     * bound to the client, and are now receiving all of the detailed information
     * about the target of our edits.
     */
    @Override public void onStartInput(EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute, restarting);
        
        // Reset our state.  We want to do this even if restarting, because
        // the underlying state of the text editor could have changed in any way.
        mComposing.setLength(0);
        setCandidatesViewShown(true);
        
        if (!restarting) {
            // Clear shift states.
            mMetaState = 0;
        }
        
        mPredictionOn = false;
        mCompletionOn = false;
        mCompletions = null;
        
        // We are now going to initialize our state based on the type of
        // text being edited.
        switch (attribute.inputType & InputType.TYPE_MASK_CLASS) {
            case InputType.TYPE_CLASS_NUMBER:
            case InputType.TYPE_CLASS_DATETIME:
                // Numbers and dates default to the symbols keyboard, with
                // no extra features.
                mCurKeyboard = mSymbolsKeyboard;
                break;
                
            case InputType.TYPE_CLASS_PHONE:
                // Phones will also default to the symbols keyboard, though
                // often you will want to have a dedicated phone keyboard.
                mCurKeyboard = mSymbolsKeyboard;
                break;
                
            case InputType.TYPE_CLASS_TEXT:
                // This is general text editing.  We will default to the
                // normal alphabetic keyboard, and assume that we should
                // be doing predictive text (showing candidates as the
                // user types).
                mCurKeyboard = mQwertyKeyboard;
                mPredictionOn = true;
                
                // We now look for a few special variations of text that will
                // modify our behavior.
                int variation = attribute.inputType & InputType.TYPE_MASK_VARIATION;
                if (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                        variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) {
                    // Do not display predictions / what the user is typing
                    // when they are entering a password.
                    mPredictionOn = false;
                }
                
                if (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                        || variation == InputType.TYPE_TEXT_VARIATION_URI
                        || variation == InputType.TYPE_TEXT_VARIATION_FILTER) {
                    // Our predictions are not useful for e-mail addresses
                    // or URIs.
                    mPredictionOn = false;
                }
                
                if ((attribute.inputType & InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE) != 0) {
                    // If this is an auto-complete text view, then our predictions
                    // will not be shown and instead we will allow the editor
                    // to supply their own.  We only show the editor's
                    // candidates when in fullscreen mode, otherwise relying
                    // own it displaying its own UI.
                    mPredictionOn = false;
                    mCompletionOn = isFullscreenMode();
                }
                
                // We also want to look at the current state of the editor
                // to decide whether our alphabetic keyboard should start out
                // shifted.
                updateShiftKeyState(attribute);
                break;
                
            default:
                // For all unknown input types, default to the alphabetic
                // keyboard with no special features.
                mCurKeyboard = mQwertyKeyboard;
                updateShiftKeyState(attribute);
        }
        
        // Update the label on the enter key, depending on what the application
        // says it will do.
        mCurKeyboard.setImeOptions(getResources(), attribute.imeOptions);
    }

    public static String convertStreamToString(InputStream is) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(is));
        StringBuilder sb = new StringBuilder();
        String line = null;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append("\n");
        }
        reader.close();
        return sb.toString();
    }

    public static String getStringFromFile (String filePath) throws Exception {
        File fl = new File(filePath);
        FileInputStream fin = new FileInputStream(fl);
        String ret = convertStreamToString(fin);
        //Make sure you close all streams.
        fin.close();
        return ret;
    }


    /**
     * This is called when the user is done editing a field.  We can use
     * this to reset our state.
     */
    @Override public void onFinishInput() {
        super.onFinishInput();
        
        // Clear current composing text and candidates.
        mComposing.setLength(0);
        updateCandidates();
        
        // We only hide the candidates window when finishing input on
        // a particular editor, to avoid popping the underlying application
        // up and down if the user is entering text into the bottom of
        // its window.
        setCandidatesViewShown(false);
        
        mCurKeyboard = mQwertyKeyboard;
        if (mInputView != null) {
            mInputView.closing();
        }
    }
    
    @Override public void onStartInputView(EditorInfo attribute, boolean restarting) {
        super.onStartInputView(attribute, restarting);
        // Apply the selected keyboard to the input view.
        mInputView.setKeyboard(mCurKeyboard);
        mInputView.closing();
        final InputMethodSubtype subtype = mInputMethodManager.getCurrentInputMethodSubtype();
        mInputView.setSubtypeOnSpaceKey(subtype);


        try {
            cppJsonString = convertStreamToString(getResources().openRawResource(R.raw.cpp));
        } catch (Exception e) {
            e.printStackTrace();
        }
        try {
             cppJson = new JSONObject(cppJsonString);
        } catch (JSONException e) {
            e.printStackTrace();
        }

        JSONArray keywords = null;
        try {
            keywords = cppJson.getJSONArray("KeyWord");


        JSONObject temp = new JSONObject();
            String name = "";
        for(int i=0; i< keywords.length();i++){

            temp = (JSONObject) keywords.get(i);
            name = temp.getString("-name");

            temp.put("-length",StringHashEng(name));




        }
            cppStringHash = new HashMap<BitSet, List<String>>() ;

            for(int i=0; i< keywords.length();i++){

                temp = (JSONObject) keywords.get(i);
                if(cppStringHash.containsKey((BitSet) temp.get("-length"))){
                    cppStringHash.get((BitSet) temp.get("-length")).add(temp.getString("-name"));
                    }
                else{
                    BitSet tDS = (BitSet) temp.get("-length");
                    List<String> tLS = new ArrayList<String>();
                    tLS.add(temp.getString("-name"));
                            cppStringHash.put(tDS, tLS);

                }





            }




        } catch (JSONException e) {
            e.printStackTrace();
        }


        }




    @Override
    public void onCurrentInputMethodSubtypeChanged(InputMethodSubtype subtype) {
        mInputView.setSubtypeOnSpaceKey(subtype);
    }

    /**
     * Deal with the editor reporting movement of its cursor.
     */
    @Override public void onUpdateSelection(int oldSelStart, int oldSelEnd,
            int newSelStart, int newSelEnd,
            int candidatesStart, int candidatesEnd) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd,
                candidatesStart, candidatesEnd);
        
        // If the current selection in the text view changes, we should
        // clear whatever candidate text we have.
        if (mComposing.length() > 0 && (newSelStart != candidatesEnd
                || newSelEnd != candidatesEnd)) {
            mComposing.setLength(0);
            updateCandidates();
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) {
                ic.finishComposingText();
            }
        }
    }

    /**
     * This tells us about completions that the editor has determined based
     * on the current text in it.  We want to use this in fullscreen mode
     * to show the completions ourself, since the editor can not be seen
     * in that situation.
     */
    @Override public void onDisplayCompletions(CompletionInfo[] completions) {
        if (mCompletionOn) {

            mCompletions = completions;
            if (completions == null) {
                setSuggestions(null, false, false);
                return;
            }
            
            List<String> stringList = new ArrayList<String>();
            for (int i = 0; i < completions.length; i++) {
                CompletionInfo ci = completions[i];
                if (ci != null) stringList.add(ci.getText().toString());
            }

            //setSuggestions(stringList, true, true);
            setSuggestions(stringList, true, true);
        }

    }
    
    /**
     * This translates incoming hard key events in to edit operations on an
     * InputConnection.  It is only needed when using the
     * PROCESS_HARD_KEYS option.
     */
    private boolean translateKeyDown(int keyCode, KeyEvent event) {
        mMetaState = MetaKeyKeyListener.handleKeyDown(mMetaState,
                keyCode, event);
        int c = event.getUnicodeChar(MetaKeyKeyListener.getMetaState(mMetaState));
        mMetaState = MetaKeyKeyListener.adjustMetaAfterKeypress(mMetaState);
        InputConnection ic = getCurrentInputConnection();
        if (c == 0 || ic == null) {
            return false;
        }
        
        boolean dead = false;

        if ((c & KeyCharacterMap.COMBINING_ACCENT) != 0) {
            dead = true;
            c = c & KeyCharacterMap.COMBINING_ACCENT_MASK;
        }
        
        if (mComposing.length() > 0) {
            char accent = mComposing.charAt(mComposing.length() -1 );
            int composed = KeyEvent.getDeadChar(accent, c);

            if (composed != 0) {
                c = composed;
                mComposing.setLength(mComposing.length()-1);
            }
        }
        
        onKey(c, null);
        
        return true;
    }
    
    /**
     * Use this to monitor key events being delivered to the application.
     * We get first crack at them, and can either resume them or let them
     * continue to the app.
     */
    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {

            case -116:
                InputConnection ic1 = getCurrentInputConnection();
                ic1.commitText("ы",1);
                break;

            case KeyEvent.KEYCODE_BACK:
                // The InputMethodService already takes care of the back
                // key for us, to dismiss the input method if it is shown.
                // However, our keyboard could be showing a pop-up window
                // that back should dismiss, so we first allow it to do that.
                if (event.getRepeatCount() == 0 && mInputView != null) {
                    if (mInputView.handleBack()) {
                        return true;
                    }
                }
                break;
                
            case KeyEvent.KEYCODE_DEL:
                // Special handling of the delete key: if we currently are
                // composing text for the user, we want to modify that instead
                // of let the application to the delete itself.
                if (mComposing.length() > 0) {
                    onKey(Keyboard.KEYCODE_DELETE, null);
                    return true;
                }
                break;
                
            case KeyEvent.KEYCODE_ENTER:
                // Let the underlying text editor always handle these.
                return false;
                
            default:
                // For all other keys, if we want to do transformations on
                // text being entered with a hard keyboard, we need to process
                // it and do the appropriate action.
                if (PROCESS_HARD_KEYS) {
                    if (keyCode == KeyEvent.KEYCODE_SPACE
                            && (event.getMetaState()&KeyEvent.META_ALT_ON) != 0) {
                        // A silly example: in our input method, Alt+Space
                        // is a shortcut for 'android' in lower case.
                        InputConnection ic = getCurrentInputConnection();
                        if (ic != null) {
                            // First, tell the editor that it is no longer in the
                            // shift state, since we are consuming this.
                            ic.clearMetaKeyStates(KeyEvent.META_ALT_ON);
                            keyDownUp(KeyEvent.KEYCODE_A);
                            keyDownUp(KeyEvent.KEYCODE_N);
                            keyDownUp(KeyEvent.KEYCODE_D);
                            keyDownUp(KeyEvent.KEYCODE_R);
                            keyDownUp(KeyEvent.KEYCODE_O);
                            keyDownUp(KeyEvent.KEYCODE_I);
                            keyDownUp(KeyEvent.KEYCODE_D);
                            // And we consume this event.
                            return true;
                        }
                    }
                    if (mPredictionOn && translateKeyDown(keyCode, event)) {
                        return true;
                    }
                }
        }
        
        return super.onKeyDown(keyCode, event);
    }

    /**
     * Use this to monitor key events being delivered to the application.
     * We get first crack at them, and can either resume them or let them
     * continue to the app.
     */
    @Override public boolean onKeyUp(int keyCode, KeyEvent event) {
        // If we want to do transformations on text being entered with a hard
        // keyboard, we need to process the up events to update the meta key
        // state we are tracking.
        if (PROCESS_HARD_KEYS) {
            if (mPredictionOn) {
                mMetaState = MetaKeyKeyListener.handleKeyUp(mMetaState,
                        keyCode, event);
            }
        }
        
        return super.onKeyUp(keyCode, event);
    }

    /**
     * Helper function to commit any text being composed in to the editor.
     */
    private void commitTyped(InputConnection inputConnection) {
        if (mComposing.length() > 0) {
            inputConnection.commitText(mComposing, mComposing.length());
            mComposing.setLength(0);
            updateCandidates();
        }
    }

    private void commitTyped(InputConnection inputConnection, String suggested) {
        if (mComposing.length() > 0) {
            inputConnection.commitText(suggested, suggested.length());
            mComposing.setLength(0);
            updateCandidates();
        }
    }



    /**
     * Helper to update the shift state of our keyboard based on the initial
     * editor state.
     */
    private void updateShiftKeyState(EditorInfo attr) {
        if (attr != null
                && mInputView != null && mQwertyKeyboard == mInputView.getKeyboard()) {
            int caps = 0;
            EditorInfo ei = getCurrentInputEditorInfo();
            if (ei != null && ei.inputType != InputType.TYPE_NULL) {
                caps = getCurrentInputConnection().getCursorCapsMode(attr.inputType);
            }
            mInputView.setShifted(mCapsLock || caps != 0);
        }
    }

    /**
     * Helper to determine if a given character code is alphabetic.
     */
    private boolean isAlphabet(int code) {
        if (Character.isLetter(code)) {
            return true;
        } else {
            return false;
        }
    }
    
    /**
     * Helper to send a key down / key up pair to the current editor.
     */
    private void keyDownUp(int keyEventCode) {
        getCurrentInputConnection().sendKeyEvent(
                new KeyEvent(KeyEvent.ACTION_DOWN, keyEventCode));
        getCurrentInputConnection().sendKeyEvent(
                new KeyEvent(KeyEvent.ACTION_UP, keyEventCode));
    }
    
    /**
     * Helper to send a character to the editor as raw key events.
     */
    private void sendKey(int keyCode) {
        switch (keyCode) {
            case '\n':
                keyDownUp(KeyEvent.KEYCODE_ENTER);
                break;
            default:
                if (keyCode >= '0' && keyCode <= '9') {
                    keyDownUp(keyCode - '0' + KeyEvent.KEYCODE_0);
                } else {
                    getCurrentInputConnection().commitText(String.valueOf((char) keyCode), 1);
                }
                break;
        }
    }

    // Implementation of KeyboardViewListener

    public void onKey(int primaryCode, int[] keyCodes) {
        MyClipboardManager mc = new MyClipboardManager();
        InputConnection ic1 = getCurrentInputConnection();
        if(primaryCode ==  -116) {

            ic1.commitText("ы", 1);
            return;
        }
        switch(primaryCode) {
            case 19:
                //sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_LEFT);
                keyDownUp(KeyEvent.KEYCODE_DPAD_UP);
                return;
            case 20:
                keyDownUp(KeyEvent.KEYCODE_DPAD_DOWN);
                return;
            case 21:
                keyDownUp(KeyEvent.KEYCODE_DPAD_LEFT);
                return;
            case 22:
                keyDownUp(KeyEvent.KEYCODE_DPAD_RIGHT);
                return;
            case -319:
                keyDownUp(KeyEvent.KEYCODE_ESCAPE);
                return;
            case -320:
                //Обработчик для клавиши копирования

                if(ic1.getSelectedText(0)!=null) {
                    mc.copyToClipboard(getApplicationContext(), ic1.getSelectedText(0).toString());
                }
                return;
            case -321:
                //Обработчик для клавиши вставки
                ic1.commitText(mc.readFromClipboard(getApplicationContext()),1);

                return;
        }
        if (isWordSeparator(primaryCode)) {
            // Handle separator
            if (mComposing.length() > 0) {
                commitTyped(getCurrentInputConnection());
            }
            sendKey(primaryCode);
            updateShiftKeyState(getCurrentInputEditorInfo());
        } else if (primaryCode == Keyboard.KEYCODE_DELETE) {
            handleBackspace();
        } else if (primaryCode == Keyboard.KEYCODE_SHIFT) {
            handleShift();
        } else if (primaryCode == Keyboard.KEYCODE_CANCEL) {
            handleClose();
            return;
        } else if (primaryCode == LatinKeyboardView.KEYCODE_OPTIONS) {
            // Show a menu or somethin'
        } else if (primaryCode == Keyboard.KEYCODE_MODE_CHANGE
                && mInputView != null) {
            Keyboard current = mInputView.getKeyboard();
            if (current == mRussianKeyboard) {
                current = mEnglishKeyboard;
            } else {
                current = mRussianKeyboard;
            }
            mQwertyKeyboard = (LatinKeyboard)current;
            mInputView.setKeyboard(current);
            //onCreateInputView().invalidate();
            mInputView.setPadding(0,0,100,0);

            if (current == mSymbolsKeyboard) {
                current.setShifted(false);
            }
        } else {
            handleCharacter(primaryCode, keyCodes);
        }
    }

    public void onText(CharSequence text) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        ic.beginBatchEdit();
        if (mComposing.length() > 0) {
            commitTyped(ic);
        }
        ic.commitText(text, 0);
        ic.endBatchEdit();
        updateShiftKeyState(getCurrentInputEditorInfo());
    }

    /**
     * Update the list of available candidates from the current composing
     * text.  This will need to be filled in by however you are determining
     * candidates.
     */
    private void updateCandidates() {
        if (!mCompletionOn) {
            if (mComposing.length() > 0) {
                ArrayList<String> list = new ArrayList<String>();
                list.add(mComposing.toString());
                setSuggestions(list, true, true);
            } else {

                setSuggestions(null, false, false);
            }
        }
    }
    
    public void setSuggestions(List<String> suggestions, boolean completions,
            boolean typedWordValid) {
        if (suggestions != null && suggestions.size() > 0) {
            setCandidatesViewShown(true);
        } else if (isExtractViewShown()) {
            setCandidatesViewShown(true);
        }
        if (mCandidateView != null) {
            if(suggestions!=null) {
                // suggestions.add("ololo");
                // suggestions.add("azazaz");
                // suggestions.add("wtf");}

                List<String> wordGeomPar=cppSujjestHash(mComposing.toString());
                if(wordGeomPar!=null){
                    suggestions.addAll(wordGeomPar);
                }

            }
            mCandidateView.setSuggestions(suggestions, completions, typedWordValid);
        }
    }

  /*  List<String> cppSujjest(String typed){
        List<String> ls = new ArrayList<String>();
        JSONArray keywords = null;
        JSONObject temp = null;
        String name = null;
        Double length = 0.;
        int count = 0;
        Double tlength = StringLengthGeom(typed);
        ls.add(String.format( "%.2f", tlength ));
        try {
            keywords = cppJson.getJSONArray("KeyWord");
            for(int i=0;i<keywords.length()&&count<7;i++){
                temp = (JSONObject) keywords.get(i);
                name = temp.getString("-name");
                length = temp.getDouble("-length");

                if(abs(tlength -length)<0.05)
                {
                    ls.add(name);
                    ls.add(String.format( "%.2f", length ));
                    count++;
                }



            }




        } catch (JSONException e) {
            e.printStackTrace();
        }



        return ls;
    }*/

   /* List<String> geomSuggestions(String typed){
        char[] cTyped = typed.toLowerCase().toCharArray();
        List<String> ls = null;
        JSONObject Keygeometry = null;
        try {
             Keygeometry = new JSONObject("{  \"keyboard\": {    \"q\": {      \"x\": \"0.0\",      \"y\": \"0.0\"    },    \"w\": {      \"x\": \"1.0\",      \"y\": \"0.0\"    },    \"e\": {      \"x\": \"2.0\",      \"y\": \"0.0\"    },    \"r\": {      \"x\": \"3.0\",      \"y\": \"0.0\"    },    \"t\": {      \"x\": \"4.0\",      \"y\": \"0.0\"    },    \"y\": {      \"x\": \"5.0\",      \"y\": \"0.0\"    },    \"u\": {      \"x\": \"6.0\",      \"y\": \"0.0\"    },    \"i\": {      \"x\": \"7.0\",      \"y\": \"0.0\"    },    \"o\": {      \"x\": \"8.0\",      \"y\": \"0.0\"    },    \"p\": {      \"x\": \"9.0\",      \"y\": \"0.0\"    },    \"a\": {      \"x\": \"0.5\",      \"y\": \"1.0\"    },    \"s\": {      \"x\": \"1.5\",      \"y\": \"1.0\"    },    \"d\": {      \"x\": \"2.5\",      \"y\": \"1.0\"    },    \"f\": {      \"x\": \"3.5\",      \"y\": \"1.0\"    },    \"g\": {      \"x\": \"4.5\",      \"y\": \"1.0\"    },    \"h\": {      \"x\": \"5.5\",      \"y\": \"1.0\"    },    \"j\": {      \"x\": \"6.5\",      \"y\": \"1.0\"    },    \"k\": {      \"x\": \"7.5\",      \"y\": \"1.0\"    },    \"l\": {      \"x\": \"8.5\",      \"y\": \"1.0\"    },    \"z\": {      \"x\": \"1.5\",      \"y\": \"2.0\"    },    \"x\": {      \"x\": \"2.5\",      \"y\": \"2.0\"    },    \"c\": {      \"x\": \"3.5\",      \"y\": \"2.0\"    },    \"v\": {      \"x\": \"4.5\",      \"y\": \"2.0\"    },    \"b\": {      \"x\": \"5.5\",      \"y\": \"2.0\"    },    \"n\": {      \"x\": \"6.5\",      \"y\": \"2.0\"    },    \"m\": {      \"x\": \"7.5\",      \"y\": \"2.0\"    }  }}");
        } catch (JSONException e) {
            e.printStackTrace();
        }
        GeometryFactory gf = new GeometryFactory(new PrecisionModel(0.001));
        JSONObject Keyboard = null;
        JSONObject key = null;
        double x = 0;
        double y = 0;
        String coord = null;
        Coordinate[] coordinates =new Coordinate[typed.length()+1];
        if (typed.length()>3) {
            for (int i = 0; i < typed.length(); i++) {
                if (Keygeometry != null) {
                    try {

                        Keyboard = Keygeometry.getJSONObject("keyboard");
                        key = Keyboard.getJSONObject(Character.toString(cTyped[i]));
                        x = key.getDouble("x");
                        y = key.getDouble("y");
                        coord += "("+x+","+y+"),";
                        Coordinate cTemp = new Coordinate(x, y);
                        coordinates[i] = cTemp;
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }

                }
            }
            coordinates[typed.length()] = coordinates[0];
            Log.d("Coordinates",coord) ;
            LinearRing[] empty = new LinearRing[0];

            LinearRing geomWord = gf.createLinearRing(coordinates);
            Polygon pl = null;
         //   pl = gf.createPolygon(geomWord);

            ls = new ArrayList<String>();
            ls.add("" + pl.getArea());
            ls.add("" + pl.getLength());
        }

        return ls;

    }

    Double StringLengthGeom (String typed){
        char[] cTyped = typed.toLowerCase().toCharArray();
        List<String> ls = null;
        JSONObject Keygeometry = null;
        String keyJsonString="";
        try {
             keyJsonString = convertStreamToString(getResources().openRawResource(R.raw.key));
        } catch (Exception e) {
            e.printStackTrace();
        }
        }

        try {
            Keygeometry = new JSONObject(keyJsonString);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        GeometryFactory gf = new GeometryFactory(new PrecisionModel(0.001));
        JSONObject Keyboard = null;
        JSONObject key = null;
        double x = 0;
        double y = 0;
        String coord = "";
        String a = "";
        Coordinate[] coordinates =new Coordinate[typed.length()];
        if (typed.length()>1) {
            for (int i = 0; i < typed.length(); i++) {
                if (Keygeometry != null) {
                    try {

                        a = Character.toString(cTyped[i]);
                        Keyboard = Keygeometry.getJSONObject("keyboard");
                        key = Keyboard.getJSONObject(Character.toString(cTyped[i]));
                        x = key.getDouble("x");
                        y = key.getDouble("y");
                        coord += "("+x+","+y+"),";
                        Coordinate cTemp = new Coordinate(x, y);
                        coordinates[i] = cTemp;
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }

                }
            }
          // coordinates[typed.length()] = coordinates[0];

               Log.d("Coord",coord);



   Double l = 0.;
            for(int j =0;j<coordinates.length-1;j++){
                l+=sqrt(pow((coordinates[j + 1].x - coordinates[j].x), 2)+pow((coordinates[j+1].y-coordinates[j].y),2));

            }

            l=l;
           return l;
        }

        return -1.0;

    }*/

    BitSet StringHashEng (String typed){
        Map<Character , Integer > bitNumber = new HashMap<Character, Integer>();
        bitNumber.put('a',0);
        bitNumber.put('q',0);
        bitNumber.put('s',1);
        bitNumber.put('z',1);
        bitNumber.put('w',1);
        bitNumber.put('d',1);
        bitNumber.put('e',2);
        bitNumber.put('r',2);
        bitNumber.put('t',2);
        bitNumber.put('f',3);
        bitNumber.put('g',3);
        bitNumber.put('c',4);
        bitNumber.put('v',4);
        bitNumber.put('x',4);
        bitNumber.put('b',5);
        bitNumber.put('n',5);
        bitNumber.put('m',5);
        bitNumber.put('h',6);
        bitNumber.put('j',6);
        bitNumber.put('k',7);
        bitNumber.put('l',7);
        bitNumber.put('i',7);
        bitNumber.put('u',8);
        bitNumber.put('y',8);
        bitNumber.put('o',9);
        bitNumber.put('p',9);
        bitNumber.put('_',10);
        bitNumber.put('1',10);
        bitNumber.put('2',10);
        bitNumber.put('3',10);
        bitNumber.put('4',10);
        bitNumber.put('5',10);
        bitNumber.put('6',10);
        bitNumber.put('7',10);
        bitNumber.put('8',10);
        bitNumber.put('9',10);
        bitNumber.put('0',10);


        BitSet typedB = new BitSet(13);
        char[] typedC = typed.toCharArray();

        for(int i = 0; i< typed.length();i++){
            try {
                typedB.set(bitNumber.get(typedC[i]).intValue(),true);
            }
            catch (Exception e) {
                e.printStackTrace();
                return new BitSet(13);
            }

        }
        Log.d("words",typedB.toString()) ;
    return typedB;
    }

   List<String> cppSujjestHash(String typed){
    List<String> tLS = null;
       List<String> sujjest = new ArrayList<String>();
       BitSet typedB = StringHashEng(typed.toLowerCase());
       Log.d("words",typedB.toString()) ;
       diff_match_patch diff = new diff_match_patch();
       LinkedList<diff_match_patch.Diff> temp_diff = null;
       for(int k = 0; k<14;k++) {
           //если есть такой код в списке
           if (cppStringHash.containsKey(typedB)) {
               tLS = cppStringHash.get(typedB);
               //проходим по списку слов с таким же хешем
               for (int i = 0; i < tLS.size(); i++) {
                   if (tLS.get(i).length() > typed.length() + 1) {
                       continue;
                   } //если разница в длинне слов больше чем 1 то пропускаем слово
                   temp_diff = diff.diff_main(tLS.get(i), typed);
                   diff_match_patch.Diff td = null;
                   String tw = "";
                   //проходим по списку разницы между набранным и тем что в списке
                   for (int j = 0; j < temp_diff.size(); j++) {
                       td = temp_diff.get(j);
                       if (td.operation == diff_match_patch.Operation.EQUAL) {
                           tw += td.text;
                       }

                   }
                   if (abs(tw.length() - typed.length()) <= 2) {
                       sujjest.add(tLS.get(i));
                   }
               }

           }
           if(k==13){continue;}
           typedB =StringHashEng(typed.toLowerCase());
           typedB.set(k,!typedB.get(k));
       }


    return sujjest;
    }


    private void handleBackspace() {
        final int length = mComposing.length();
        if (length > 1) {
            mComposing.delete(length - 1, length);
            getCurrentInputConnection().setComposingText(mComposing, 1);
            updateCandidates();
        } else if (length > 0) {
            mComposing.setLength(0);
            getCurrentInputConnection().commitText("", 0);
            updateCandidates();
        } else {
            keyDownUp(KeyEvent.KEYCODE_DEL);
        }
        updateShiftKeyState(getCurrentInputEditorInfo());
    }

    private void handleShift() {
        if (mInputView == null) {
            return;
        }
        
        Keyboard currentKeyboard = mInputView.getKeyboard();
        if (mQwertyKeyboard == currentKeyboard) {
            // Alphabet keyboard
            checkToggleCapsLock();
            mInputView.setShifted(mCapsLock || !mInputView.isShifted());
        } else if (mRussianKeyboard == currentKeyboard) {
            checkToggleCapsLock();
            mInputView.setShifted(mCapsLock || !mInputView.isShifted());
        } else if (currentKeyboard == mSymbolsKeyboard) {
            mSymbolsKeyboard.setShifted(true);
            mInputView.setKeyboard(mSymbolsShiftedKeyboard);
            mSymbolsShiftedKeyboard.setShifted(true);
        } else if (currentKeyboard == mSymbolsShiftedKeyboard) {
            mSymbolsShiftedKeyboard.setShifted(false);
            mInputView.setKeyboard(mSymbolsKeyboard);
            mSymbolsKeyboard.setShifted(false);
        }
    }
    
    private void handleCharacter(int primaryCode, int[] keyCodes) {
        if (isInputViewShown()) {
            if (mInputView.isShifted()) {
                primaryCode = Character.toUpperCase(primaryCode);
            }
        }
        if (isAlphabet(primaryCode) && mPredictionOn) {
            mComposing.append((char) primaryCode);
            getCurrentInputConnection().setComposingText(mComposing, 1);
            updateShiftKeyState(getCurrentInputEditorInfo());
            updateCandidates();
        } else {
            getCurrentInputConnection().commitText(
                    String.valueOf((char) primaryCode), 1);
        }
    }

    private void handleClose() {
        commitTyped(getCurrentInputConnection());
        requestHideSelf(0);
        mInputView.closing();
    }

    private void checkToggleCapsLock() {
        long now = System.currentTimeMillis();
        if (mLastShiftTime + 800 > now) {
            mCapsLock = !mCapsLock;
            mLastShiftTime = 0;
        } else {
            mLastShiftTime = now;
        }
    }
    
    private String getWordSeparators() {
        return mWordSeparators;
    }
    
    public boolean isWordSeparator(int code) {
        String separators = getWordSeparators();
        return separators.contains(String.valueOf((char)code));
    }

    public void pickDefaultCandidate() {
        pickSuggestionManually(0);
    }
    
    public void pickSuggestionManually(int index) {
        if (mCompletionOn && mCompletions != null && index >= 0
                && index < mCompletions.length) {
            CompletionInfo ci = mCompletions[index];
            getCurrentInputConnection().commitCompletion(ci);
            if (mCandidateView != null) {
                mCandidateView.clear();
            }
            updateShiftKeyState(getCurrentInputEditorInfo());
        } else if (mComposing.length() > 0) {
            // If we were generating candidate suggestions for the current
            // text, we would commit one of them here.  But for this sample,
            // we will just commit the current text.

            commitTyped(getCurrentInputConnection(),mCandidateView.mSuggestions.get(index));
        }
    }
    
    public void swipeRight() {
        if (mCompletionOn) {
            pickDefaultCandidate();
        }
    }
    
    public void swipeLeft() {
        handleBackspace();
    }

    public void swipeDown() {
        handleClose();
    }

    public void swipeUp() {
    }
    
    public void onPress(int primaryCode) {
    }
    
    public void onRelease(int primaryCode) {
    }
}
