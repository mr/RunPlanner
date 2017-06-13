package net.ma.ttrobinson.runplanner

import android.os.Bundle
import android.support.design.widget.BottomNavigationView
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.Toolbar

class MainActivity : AppCompatActivity() {
    companion object {
        val SHARED_PREF_COMPLETION_KEY = "completion"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById(R.id.toolbar) as Toolbar
        setSupportActionBar(toolbar)

        val timerFragment = TimerFragment()
        val scheduleFragment = ScheduleFragment()

        supportFragmentManager
            .beginTransaction()
            .replace(R.id.content, timerFragment)
            .commit()

        val navigation = findViewById(R.id.navigation) as BottomNavigationView
        navigation.setOnNavigationItemSelectedListener {
            val fragment = when (it.itemId) {
                R.id.navigation_timer -> timerFragment
                R.id.navigation_schedules -> scheduleFragment
                else -> null
            }

            if (fragment != null) {
                supportFragmentManager
                    .beginTransaction()
                    .replace(R.id.content, fragment)
                    .commit()
                true
            } else {
                false
            }
        }
    }
}
