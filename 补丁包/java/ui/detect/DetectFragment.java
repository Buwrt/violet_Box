package com.violet.box.ui.detect;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.violet.box.R;

/**
 * Environment-detection screen, restored from b42df36.
 *
 * <p>The original called straight into RootBeer from onViewCreated; that library is no longer
 * published to any reachable Maven mirror, so the two classes (RootBeer + Const/QLog/RootBeerNative)
 * are vendored under com.scottyab.rootbeer, and the sweep is combined with the project's own
 * {@link com.violet.box.data.detector.RootDetector}. Rendering lives in {@link DetectViewBinder}
 * so the safety tab can host the exact same panel without a Fragment transaction.
 */
public class DetectFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_detect, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        DetectViewBinder.bind(view, requireContext());
    }
}
