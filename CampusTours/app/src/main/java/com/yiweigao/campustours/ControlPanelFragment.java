package com.yiweigao.campustours;

import android.app.Fragment;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;

/**
 * Created by yiweigao on 2/26/15.
 */
public class ControlPanelFragment extends Fragment {

    private static float BUTTON_ALPHA = 0.80f;
    
    private AudioPlayer mAudioPlayer = AudioPlayer.getInstance();
    
    private View mInflatedView;
    private ImageButton mRwndButton;
    private ImageButton mPlayButton;
    private ImageButton mNextButton;


    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {

        mInflatedView = inflater.inflate(R.layout.control_panel_fragment, container, false);

        mRwndButton = (ImageButton) mInflatedView.findViewById(R.id.control_panel_rewind_button);
        mPlayButton = (ImageButton) mInflatedView.findViewById(R.id.control_panel_play_button);
        mNextButton = (ImageButton) mInflatedView.findViewById(R.id.control_panel_next_button);

        mRwndButton.setAlpha(BUTTON_ALPHA);
        mPlayButton.setAlpha(BUTTON_ALPHA);
        mNextButton.setAlpha(BUTTON_ALPHA);

        mAudioPlayer.create(getActivity());

        mRwndButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mAudioPlayer.rewind();
            }
        });

        mPlayButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mAudioPlayer.togglePlayback();
                if (mAudioPlayer.isPlaying()) {
                    mPlayButton.setImageResource(R.mipmap.pause_icon);
                } else {
                    mPlayButton.setImageResource(R.mipmap.play_icon);
                }
            }
        });

        mNextButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mAudioPlayer.next();
            }
        });

        return mInflatedView;

    }
}