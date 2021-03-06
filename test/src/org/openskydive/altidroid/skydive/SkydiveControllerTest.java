package org.openskydive.altidroid.skydive;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;

import org.openskydive.altidroid.log.LogEntry;
import org.openskydive.altidroid.skydive.SkydiveState.Type;
import org.openskydive.altidroid.test.R;
import org.openskydive.altidroid.util.Units;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.test.InstrumentationTestCase;
import android.test.mock.MockContentResolver;

public class SkydiveControllerTest extends InstrumentationTestCase implements SkydiveListener {
    public class AlarmsProvider extends ContentProvider {
        public AlarmsProvider() {
            attachInfo(mContext, null);
        }

        @Override
        public int delete(Uri uri, String selection, String[] selectionArgs) {
            fail();
            return 0;
        }

        @Override
        public String getType(Uri uri) {
            fail();
            return null;
        }

        @Override
        public Uri insert(Uri uri, ContentValues values) {
            fail();
            return null;
        }

        @Override
        public boolean onCreate() {
            return true;
        }

        @Override
        public Cursor query(Uri uri, String[] projection, String selection,
                String[] selectionArgs, String sortOrder) {
            assertEquals(Alarm.Columns.CONTENT_URI, uri);
            assertEquals(Alarm.Columns.QUERY_COLUMNS, projection);
            MatrixCursor cursor = new MatrixCursor(Alarm.Columns.QUERY_COLUMNS);
            cursor.addRow(makeAlarm(1, Alarm.Type.FREEFALL, Units.fromFoot(4500)));
            cursor.addRow(makeAlarm(2, Alarm.Type.FREEFALL, Units.fromFoot(3500)));
            cursor.addRow(makeAlarm(3, Alarm.Type.FREEFALL, Units.fromFoot(1500)));
            cursor.addRow(makeAlarm(4, Alarm.Type.CANOPY, Units.fromFoot(900)));
            cursor.addRow(makeAlarm(5, Alarm.Type.CANOPY, Units.fromFoot(600)));
            cursor.addRow(makeAlarm(6, Alarm.Type.CANOPY, Units.fromFoot(300)));
            return cursor;
        }

        private Object[] makeAlarm(int id, Alarm.Type type, int alt) {
            return new Object[] { id, "", type.ordinal(), alt, "", "" };
        }

        @Override
        public int update(Uri uri, ContentValues values, String selection,
                String[] selectionArgs) {
            fail();
            return 0;
        }
    }

    public class LogProvider extends ContentProvider {
        public LogProvider() {
            attachInfo(mContext, null);
        }

        @Override
        public int update(Uri uri, ContentValues values, String selection,
                String[] selectionArgs) {
            fail();
            return 0;
        }

        @Override
        public Cursor query(Uri uri, String[] projection, String selection,
                String[] selectionArgs, String sortOrder) {
            MatrixCursor cursor = new MatrixCursor(Alarm.Columns.QUERY_COLUMNS);
            return cursor;
        }

        @Override
        public boolean onCreate() {
            return true;
        }

        @Override
        public Uri insert(Uri uri, ContentValues values) {
            mLogged = values;
            return null;
        }

        @Override
        public String getType(Uri uri) {
            fail();
            return null;
        }

        @Override
        public int delete(Uri uri, String selection, String[] selectionArgs) {
            fail();
            return 0;
        }
    }

    private SkydiveController mController;
    private ArrayList<Alarm> mFiredAlarms;
    private Context mContext;
    private SkydiveState mPrevState = null;

    public ContentValues mLogged;

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        final MockContentResolver contentResolver = new MockContentResolver();
        contentResolver.addProvider("", new AlarmsProvider());
        contentResolver.addProvider(".log", new LogProvider());

        mContext = getInstrumentation().getContext();
        mController = new SkydiveController(new ContextWrapper(mContext) {
            @Override
            public ContentResolver getContentResolver() {
                return contentResolver;
            }
        });

        mFiredAlarms = new ArrayList<Alarm>();
    }

    public void testStateDetectionAndAlarms() {
        InputStream log = mContext.getResources().openRawResource(R.raw.plane_log);
        MockAltimeter altimeter = new MockAltimeter(mContext, new InputStreamReader(log));

        mController.setAltimeter(altimeter);
        mController.registerListener(this);

        altimeter.replayAll();

        assertEquals(5, mFiredAlarms.size());
        assertEquals(Alarm.Type.FREEFALL, mFiredAlarms.get(0).getType());
        assertEquals(Alarm.Type.FREEFALL, mFiredAlarms.get(1).getType());
        assertEquals(Alarm.Type.CANOPY, mFiredAlarms.get(2).getType());
        assertEquals(Alarm.Type.CANOPY, mFiredAlarms.get(3).getType());
        assertEquals(Alarm.Type.CANOPY, mFiredAlarms.get(4).getType());

        assertEquals(Type.GROUND, mPrevState.getType());

        assertNotNull(mLogged);
        int exitAlt = mLogged.getAsInteger(LogEntry.Columns.EXIT_ALTITUDE);
        assertTrue("exit: " + Units.toFoot(exitAlt),
                exitAlt > Units.fromFoot(12800) && exitAlt < Units.fromFoot(12850));

        int deployAlt = mLogged.getAsInteger(LogEntry.Columns.DEPLOY_ALTITUDE);
        assertTrue("deploy: " + Units.toFoot(deployAlt),
                deployAlt > Units.fromFoot(3100) && deployAlt < Units.fromFoot(3150));
    }

    @Override
    public void update(SkydiveState state) {
        if (mPrevState == null) {
            mPrevState = state;
            return;
        }
        Type prev = mPrevState.getType();
        Type t = state.getType();
        if (t != prev) {
            if (t == Type.GROUND) {
                assertTrue(state.toString(), prev == Type.UNKNOWN || prev == Type.CANOPY);
            } else if (t == Type.CLIMB) {
                assertEquals(state.toString(), Type.GROUND, prev);
            } else if (t == Type.FREEFALL) {
                assertEquals(state.toString() + mPrevState.toString(), Type.CLIMB, prev);
            } else if (t == Type.CANOPY) {
                assertEquals(state.toString(), Type.FREEFALL, prev);
            }
        }
        mPrevState = state;
    }

    @Override
    public void alarm(Alarm alarm) {
        assertFalse("fired twice: " + alarm.toString(), mFiredAlarms.contains(alarm));
        mFiredAlarms.add(alarm);
    }
}
