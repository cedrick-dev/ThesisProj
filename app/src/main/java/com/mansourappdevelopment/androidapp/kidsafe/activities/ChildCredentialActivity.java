package com.mansourappdevelopment.androidapp.kidsafe.activities;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.RadioGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.FirebaseDatabase;
import com.mansourappdevelopment.androidapp.kidsafe.R;
import com.mansourappdevelopment.androidapp.kidsafe.models.Child;

public class ChildCredentialActivity extends AppCompatActivity {
    private TextInputEditText edtParentEmail;
    private TextInputEditText edtChildName;
    private TextInputEditText edtChildAge;
    private RadioGroup rgGender;
    private Button btnCompleteSetup;
    
    private String parentEmail = "";
    private String childEmail = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_child_credential);
        
        Intent intent = getIntent();
        if (intent != null) {
            parentEmail = intent.getStringExtra("PARENT_EMAIL");
            childEmail = intent.getStringExtra("CHILD_EMAIL");
        }

        edtParentEmail = findViewById(R.id.edtParentEmail);
        edtChildName = findViewById(R.id.edtChildName);
        edtChildAge = findViewById(R.id.edtChildAge);
        rgGender = findViewById(R.id.rgGender);
        btnCompleteSetup = findViewById(R.id.btnCompleteSetup);
        
        if (parentEmail != null && !parentEmail.isEmpty()) {
            edtParentEmail.setText(parentEmail);
        }

        btnCompleteSetup.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                completeSetup();
            }
        });
    }
    
    private void completeSetup() {
        String name = edtChildName.getText().toString().trim();
        String age = edtChildAge.getText().toString().trim();
        
        if (TextUtils.isEmpty(name)) {
            edtChildName.setError("Name is required");
            edtChildName.requestFocus();
            return;
        }

        if (TextUtils.isEmpty(age)) {
            edtChildAge.setError("Age is required");
            edtChildAge.requestFocus();
            return;
        }

        String gender = "Boy";
        int checkedId = rgGender.getCheckedRadioButtonId();
        if (checkedId == R.id.rbGirl) {
            gender = "Girl";
        }

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user != null) {
            String uid = user.getUid();
            Child child = new Child(name, childEmail, parentEmail);
            child.setGender(gender);
            child.setAge(age);
            child.setDeviceModel(Build.MANUFACTURER + " " + Build.MODEL);
            
            // Show loading or disable button
            btnCompleteSetup.setEnabled(false);
            btnCompleteSetup.setText("Saving...");

            FirebaseDatabase.getInstance().getReference("users").child("childs").child(uid).setValue(child)
                .addOnCompleteListener(new OnCompleteListener<Void>() {
                    @Override
                    public void onComplete(@NonNull Task<Void> task) {
                        btnCompleteSetup.setEnabled(true);
                        btnCompleteSetup.setText("Complete Setup");
                        
                        if (task.isSuccessful()) {
                            Toast.makeText(ChildCredentialActivity.this, "Device Registered!", Toast.LENGTH_SHORT).show();
                            startActivity(new Intent(ChildCredentialActivity.this, ChildSignedInActivity.class));
                            finish();
                        } else {
                            Toast.makeText(ChildCredentialActivity.this, "Failed to save profile: " + task.getException().getMessage(), Toast.LENGTH_LONG).show();
                        }
                    }
                });
        } else {
            Toast.makeText(this, "Authentication error. Please restart the app.", Toast.LENGTH_LONG).show();
        }
    }
}
