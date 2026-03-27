package com.example.newconstructionappwithlocationtracking.interfaces

/**
 * Interface for fragments to communicate with MainActivity for navigation
 * This ensures bottom navigation tab state is always synchronized
 */
interface NavigationHost {
    /**
     * Navigate to a specific tab and update bottom navigation
     * @param tabId The R.id of the navigation item (e.g., R.id.nav_home)
     */
    fun navigateToTab(tabId: Int)

    /**
     * Update the selected tab in bottom navigation without loading fragment
     * Useful when fragment is already loaded but tab indicator needs update
     * @param tabId The R.id of the navigation item
     */
    fun updateSelectedTab(tabId: Int)
}
