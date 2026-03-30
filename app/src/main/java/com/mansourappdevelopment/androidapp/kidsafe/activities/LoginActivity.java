package com.mansourappdevelopment.androidapp.kidsafe.activities;

import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentManager;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.mansourappdevelopment.androidapp.kidsafe.R;
import com.mansourappdevelopment.androidapp.kidsafe.dialogfragments.LoadingDialogFragment;
import com.mansourappdevelopment.androidapp.kidsafe.utils.Constant;
import com.mansourappdevelopment.androidapp.kidsafe.utils.LocaleUtils;
import com.mansourappdevelopment.androidapp.kidsafe.utils.SharedPrefsUtils;
import com.mansourappdevelopment.androidapp.kidsafe.models.Child;

import com.journeyapps.barcodescanner.ScanOptions;
import com.journeyapps.barcodescanner.ScanContract;
import androidx.activity.result.ActivityResultLauncher;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

public class LoginActivity extends AppCompatActivity {
	private static final String TAG = "LoginActivityTAG";
	private Button btnChildQrLogin;
	private ProgressBar progressBar;
	private FirebaseAuth auth;
	private FragmentManager fragmentManager;
	private FirebaseDatabase firebaseDatabase;
	private DatabaseReference databaseReference;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_login);

		fragmentManager = getSupportFragmentManager();
		LocaleUtils.setAppLanguage(this);

		auth = FirebaseAuth.getInstance();
		firebaseDatabase = FirebaseDatabase.getInstance();
		databaseReference = firebaseDatabase.getReference("users");

		progressBar = findViewById(R.id.progressBar);

		btnChildQrLogin = findViewById(R.id.btnChildQrLogin);
		btnChildQrLogin.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				startChildQrLogin();
			}
		});

	}

	@Override
	protected void onStart() {
		super.onStart();
		// On a fresh install, SharedPreferences are wiped, so CHILD_FIRST_LAUNCH
		// defaults to true. Use this as the signal to clear any stale Firebase
		// anonymous session so the user must go through onboarding again.
		boolean isFirstLaunch = SharedPrefsUtils.getBooleanPreference(
				this, Constant.CHILD_FIRST_LAUNCH, true);
		if (isFirstLaunch) {
			// Sign out any persisted session (happens after reinstall)
			auth.signOut();
			return; // Stay on login screen
		}
		// Returning user — auto-navigate if still authenticated
		FirebaseUser user = auth.getCurrentUser();
		if (user != null) {
			databaseReference.child("childs").child(user.getUid()).addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
				@Override
				public void onDataChange(@androidx.annotation.NonNull com.google.firebase.database.DataSnapshot snapshot) {
					if (snapshot.exists() && snapshot.hasChild("parentEmail")) {
						startChildSignedInActivity();
					} else {
						// Node missing (likely deleted by parent), sign out to allow fresh QR setup
						auth.signOut();
					}
				}

				@Override
				public void onCancelled(@androidx.annotation.NonNull com.google.firebase.database.DatabaseError error) {
				}
			});
		}
	}

	private void startChildSignedInActivity() {
		Intent intent = new Intent(this, ChildSignedInActivity.class);
		startActivity(intent);
		finish();
	}

	private ActivityResultLauncher<ScanOptions> barcodeLauncher = registerForActivityResult(new ScanContract(), result -> {
		if(result.getContents() != null) {
			String jsonStr = result.getContents();
			try {
				JsonObject jsonObject = new Gson().fromJson(jsonStr, JsonObject.class);
				if (jsonObject.has("parentEmail")) {
					String parentEmail = jsonObject.get("parentEmail").getAsString();
					handleChildDeviceAuth(parentEmail);
				} else {
					Toast.makeText(this, "Invalid QR Code format", Toast.LENGTH_SHORT).show();
				}
			} catch (Exception e) {
				Toast.makeText(this, "Failed to parse QR Code", Toast.LENGTH_SHORT).show();
				e.printStackTrace();
			}
		}
	});

	private void startChildQrLogin() {
		ScanOptions options = new ScanOptions();
		options.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
		options.setPrompt("Scan Parent's QR Code from Dashboard");
		options.setCameraId(0);
		options.setBeepEnabled(false);
		options.setBarcodeImageEnabled(true);
		options.setOrientationLocked(true); // Lock to portrait
		barcodeLauncher.launch(options);
	}

	private void handleChildDeviceAuth(String parentEmail) {
		final LoadingDialogFragment loadingDialogFragment = new LoadingDialogFragment();
		startLoadingFragment(loadingDialogFragment);

		String androidId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
		if (androidId == null || androidId.isEmpty()) {
			androidId = "unknown_device_" + System.currentTimeMillis();
		}
		
		String email = androidId + "@aegisnet.child";
		String password = androidId + "_secure_AegisNet";

		auth.signInWithEmailAndPassword(email, password).addOnCompleteListener(this, task -> {
			if (task.isSuccessful()) {
				FirebaseUser user = auth.getCurrentUser();
				if (user != null) {
					databaseReference.child("childs").child(user.getUid()).addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
						@Override
						public void onDataChange(@androidx.annotation.NonNull com.google.firebase.database.DataSnapshot snapshot) {
							stopLoadingFragment(loadingDialogFragment);
							if (snapshot.exists() && snapshot.hasChild("parentEmail")) {
								// Device registered and DB node exists completely
								startChildSignedInActivity();
							} else {
								// DB node missing (likely deleted by parent), force setup again
								Intent credentialIntent = new Intent(LoginActivity.this, ChildCredentialActivity.class);
								credentialIntent.putExtra("PARENT_EMAIL", parentEmail);
								credentialIntent.putExtra("CHILD_EMAIL", email);
								startActivity(credentialIntent);
								finish();
							}
						}

						@Override
						public void onCancelled(@androidx.annotation.NonNull com.google.firebase.database.DatabaseError error) {
							stopLoadingFragment(loadingDialogFragment);
							Toast.makeText(LoginActivity.this, "Database error: " + error.getMessage(), Toast.LENGTH_SHORT).show();
						}
					});
				} else {
					stopLoadingFragment(loadingDialogFragment);
				}
			} else {
				// Failed to sign in, assume not registered, attempt creation
				auth.createUserWithEmailAndPassword(email, password).addOnCompleteListener(this, createTask -> {
					stopLoadingFragment(loadingDialogFragment);
					if (createTask.isSuccessful()) {
						FirebaseUser user = auth.getCurrentUser();
						if (user != null) {
							Intent credentialIntent = new Intent(LoginActivity.this, ChildCredentialActivity.class);
							credentialIntent.putExtra("PARENT_EMAIL", parentEmail);
							credentialIntent.putExtra("CHILD_EMAIL", email);
							startActivity(credentialIntent);
							finish();
						}
					} else {
						Toast.makeText(LoginActivity.this, "Failed to register device: " + createTask.getException().getMessage(), Toast.LENGTH_LONG).show();
					}
				});
			}
		});
	}

	private void startLoadingFragment(LoadingDialogFragment loadingDialogFragment) {
		loadingDialogFragment.setCancelable(false);
		loadingDialogFragment.show(fragmentManager, Constant.LOADING_FRAGMENT);
	}

	private void stopLoadingFragment(LoadingDialogFragment loadingDialogFragment) {
		loadingDialogFragment.dismiss();
	}

}
