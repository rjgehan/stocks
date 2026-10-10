import { Outlet, createFileRoute } from '@tanstack/react-router'

export const Route = createFileRoute('/_app/settings')({
  staticData: { chrome: 'full', phone: 'root', title: 'Menu' },
  component: () => (
    <div className="scrollbar-thin h-full overflow-y-auto">
      <div className="mx-auto w-full max-w-3xl px-4 py-4 desktop:px-6">
        <Outlet />
      </div>
    </div>
  ),
})
