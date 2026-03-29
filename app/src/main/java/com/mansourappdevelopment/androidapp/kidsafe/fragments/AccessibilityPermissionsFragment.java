package com.mansourappdevelopment.androidapp.kidsafe.fragments;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.mansourappdevelopment.androidapp.kidsafe.R;
import com.mansourappdevelopment.androidapp.kidsafe.interfaces.OnFragmentChangeListener;
import com.mansourappdevelopment.androidapp.kidsafe.utils.Constant;
import com.kidsafe.secure.services.NsfwAccessibilityService;

/**
 * Final onboarding step: asks the user to enable the NSFW Content Filter
 * accessibility service before completing setup.
 */
public class AccessibilityPermissionsFragment extends Fragment {

    private Switch switchAccessibilityPermission;
    private Button btnAccessibilityNext;
    private Button btnAccessibilityPrev;
    private OnFragmentChangeListener onFragmentChangeListener;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_permissions_accessibility, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        onFragmentChangeListener = (OnFragmentChangeListener) requireActivity();

        switchAccessibilityPermission = view.findViewById(R.id.switchAccessibilityPermission);
        btnAccessibilityNext = view.findViewById(R.id.btnAccessibilityNext);
        btnAccessibilityPrev = view.findViewById(R.id.btnAccessibilityPrev);

        // Open Accessibility Settings when the user toggles the switch
        switchAccessibilityPermission.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked && !isAccessibilityServiceEnabled()) {
                // Prevent the switch from staying checked — user must enable it manually
                buttonView.setChecked(false);
                Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                startActivity(intent);
            }
        });

        btnAccessibilityNext.setOnClickListener(v -> {
            if (isAccessibilityServiceEnabled()) {
                onFragmentChangeListener.onFragmentChange(Constant.PERMISSIONS_FRAGMENTS_FINISH);
            } else {
                Toast.makeText(requireContext(),
                        "Please enable the Content Filter (Accessibility Service) to continue.",
                        Toast.LENGTH_LONG).show();
            }
        });

        btnAccessibilityPrev.setOnClickListener(v ->
                onFragmentChangeListener.onFragmentChange(Constant.PERMISSIONS_SETTINGS_FRAGMENT));
    }

    @Override
    public void onResume() {
        super.onResume();
        // Refresh switch state when user returns from Accessibility Settings
        switchAccessibilityPermission.setChecked(isAccessibilityServiceEnabled());
    }

    /**
     * Returns true if NsfwAccessibilityService is listed in the system's enabled
     * accessibility services, meaning the user has turned it on.
     */
    private boolean isAccessibilityServiceEnabled() {
        Context context = requireContext();
        String enabledServices = Settings.Secure.getString(
                context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);

        if (enabledServices == null) return false;

        // The service component name as registered in the manifest
        ComponentName target = new ComponentName(
                context.getPackageName(),
                NsfwAccessibilityService.class.getName());

        // Each entry is "package/className" separated by colons
        for (String entry : enabledServices.split(":")) {
            try {
                if (ComponentName.unflattenFromString(entry) != null &&
                        ComponentName.unflattenFromString(entry).equals(target)) {
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }
}
