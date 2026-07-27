"use client";

import { useEffect, useState } from "react";
import type { Session } from "next-auth";
import { Loader2, AlertCircle, Sparkles } from "lucide-react";
import { Header } from "@/components/layout/Header";
import { Footer } from "@/components/landing/Footer";
import { WelcomeBar } from "@/components/home/authenticated/WelcomeBar";
import { FavoriteVenuesSection } from "@/components/home/authenticated/FavoriteVenuesSection";
import { AiRecommendationsSection } from "@/components/home/authenticated/AiRecommendationsSection";
import { CommunityFeedSection } from "@/components/home/authenticated/CommunityFeedSection";
import { PersonalStatsSection } from "@/components/home/authenticated/PersonalStatsSection";
import { HomeAmbientBackground } from "@/components/home/authenticated/decor/HomeAmbientBackground";
import {
  fetchHomeDashboard,
  mapVenueToCard,
  type HomeDashboardResponse,
} from "@/lib/home-api";
import { Button } from "@/components/ui/button";

type AuthenticatedHomePageProps = {
  user: NonNullable<Session["user"]>;
};

function HomeLoadingShell() {
  return (
    <div className="relative flex min-h-screen flex-col overflow-hidden">
      <HomeAmbientBackground />
      <Header />
      <div className="flex flex-1 flex-col items-center justify-center gap-4 py-24">
        <div className="relative">
          <div className="absolute inset-0 animate-ping rounded-full bg-emerald-400/30" />
          <Loader2 className="relative h-12 w-12 animate-spin text-emerald-700" />
        </div>
        <p className="animate-pulse text-sm font-medium text-emerald-800/80">
          Đang tải không gian thể thao của bạn...
        </p>
        <Sparkles className="h-5 w-5 text-amber-400 animate-sparkle" />
      </div>
    </div>
  );
}

export function AuthenticatedHomePage({ user }: AuthenticatedHomePageProps) {
  const displayName =
    [user.firstName, user.lastName].filter(Boolean).join(" ").trim() ||
    user.name?.trim() ||
    (user.email ? user.email.split("@")[0] : "") ||
    "bạn";

  const [dashboard, setDashboard] = useState<HomeDashboardResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const loadDashboard = () => {
    setLoading(true);
    setError(null);
    fetchHomeDashboard()
      .then(setDashboard)
      .catch((err: unknown) => {
        setError(err instanceof Error ? err.message : "Không tải được dữ liệu trang chủ.");
      })
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    loadDashboard();
  }, []);

  if (loading) {
    return <HomeLoadingShell />;
  }

  if (error || !dashboard) {
    return (
      <div className="relative flex min-h-screen flex-col">
        <HomeAmbientBackground />
        <Header />
        <div className="flex flex-1 flex-col items-center justify-center gap-4 px-4 py-24">
          <AlertCircle className="h-12 w-12 text-destructive" />
          <p className="text-center text-muted-foreground">{error ?? "Lỗi không xác định"}</p>
          <Button
            onClick={loadDashboard}
            className="home-cta-shine rounded-xl bg-green-800 hover:bg-green-900"
          >
            Thử lại
          </Button>
        </div>
      </div>
    );
  }

  const recentlyPlayedVenues = dashboard.recentlyPlayedVenues.map(mapVenueToCard);
  const recommendedVenues = dashboard.recommendedVenues.map(mapVenueToCard);

  return (
    <div className="relative min-h-screen overflow-x-hidden">
      <HomeAmbientBackground />
      <Header />
      <main className="relative z-10">
        <WelcomeBar
          displayName={displayName}
          bookingCount={dashboard.totalBookingCount}
          favoriteCount={dashboard.recentlyPlayedVenueCount}
          rewardPoints={dashboard.rewardPoints}
        />
        <FavoriteVenuesSection venues={recentlyPlayedVenues} />
        <AiRecommendationsSection venues={recommendedVenues} />
        {dashboard.communityEvents.length > 0 && (
          <CommunityFeedSection events={dashboard.communityEvents} />
        )}
        <PersonalStatsSection
          totalHours={dashboard.personalStats.totalHours}
          venuesVisited={dashboard.personalStats.venuesVisited}
          favoriteSport={dashboard.personalStats.favoriteSport}
        />
      </main>
      <Footer />
    </div>
  );
}
