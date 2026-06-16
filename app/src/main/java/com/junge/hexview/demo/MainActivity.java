package com.junge.hexview.demo;

import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.junge.hexview.HexView;

public class MainActivity extends AppCompatActivity {

    private TextView fileNameView;
    private HexView contentView;
    private ActivityResultLauncher<String[]> openDocumentLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        MaterialToolbar topAppBar = findViewById(R.id.topAppBar);
        setSupportActionBar(topAppBar);

        fileNameView = findViewById(R.id.fileNameView);
        contentView = findViewById(R.id.contentView);

        openDocumentLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri != null) {
                        showFileContent(uri);
                    }
                }
        );

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        showPlaceholder();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_open_file) {
            openDocumentLauncher.launch(new String[]{"*/*"});
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showPlaceholder() {
        fileNameView.setText(R.string.no_file_selected);
        contentView.clear();
    }

    private void showFileContent(Uri uri) {
        String displayName = FileNameUtils.getDisplayName(this, uri);
        fileNameView.setText(displayName == null ? getString(R.string.no_file_selected) : displayName);
        contentView.setUri(uri);
    }
}
