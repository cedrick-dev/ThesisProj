package com.mansourappdevelopment.androidapp.kidsafe.activities;

import android.content.Intent;
import android.os.Bundle;
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
		// Automatically check if already logged in
		FirebaseUser user = auth.getCurrentUser();
		if (user != null) {
			startChildSignedInActivity();
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
					handleChildAnonymousLogin(parentEmail);
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
		barcodeLauncher.launch(options);
	}

	private void handleChildAnonymousLogin(String parentEmail) {
		final LoadingDialogFragment loadingDialogFragment = new LoadingDialogFragment();
		startLoadingFragment(loadingDialogFragment);

		auth.signInAnonymously().addOnCompleteListener(this, task -> {
			stopLoadingFragment(loadingDialogFragment);
			if (task.isSuccessful()) {
				FirebaseUser user = auth.getCurrentUser();
				if (user != null) {
					String anonymousUid = user.getUid();
					Child child = new Child("Child Device", anonymousUid + "@anonymous.com", parentEmail);
					databaseReference.child("childs").child(anonymousUid).setValue(child).addOnCompleteListener(dbTask -> {
						if (dbTask.isSuccessful()) {
							startChildSignedInActivity();
						} else {
							Toast.makeText(LoginActivity.this, "Failed to register child device in database", Toast.LENGTH_SHORT).show();
						}
					});
				}
			} else {
				Toast.makeText(LoginActivity.this, "Failed to sign in anonymously", Toast.LENGTH_SHORT).show();
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
