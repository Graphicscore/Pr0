package com.pr0gramm.app;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentStatePagerAdapter;
import android.support.v4.view.ViewPager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.pr0gramm.app.feed.FeedProxy;

import roboguice.fragment.RoboFragment;
import roboguice.inject.InjectView;

/**
 */
@SuppressLint("ValidFragment")
public class PostPagerFragment extends RoboFragment {
    private final FeedProxy proxy;
    private final int idx;

    @InjectView(R.id.pager)
    private ViewPager viewPager;

    public PostPagerFragment(FeedProxy proxy, int idx) {
        setRetainInstance(true);

        this.proxy = proxy;
        this.idx = idx;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        return inflater.inflate(R.layout.fragment_post_pager, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        viewPager.setAdapter(new PostAdapter());
        viewPager.setCurrentItem(idx);
    }

    private class PostAdapter extends FragmentStatePagerAdapter {
        PostAdapter() {
            super(getChildFragmentManager());
        }

        @Override
        public Fragment getItem(int position) {
            return PostFragment.newInstance(proxy.getItemAt(position));
        }

        @Override
        public int getCount() {
            return proxy.getItemCount();
        }
    }
}
